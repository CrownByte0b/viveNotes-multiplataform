import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Dependency-free checks for the Compose resource catalog and application UI text. */
class ResourceLint {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)\\$[sd]");
    private static final Pattern UNSUPPORTED_PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private final List<String> errors = new ArrayList<>();
    private int sourceCount;

    public static void main(String[] arguments) throws Exception {
        var root = Path.of(arguments.length == 0 ? "." : arguments[0]).toAbsolutePath().normalize();
        var lint = new ResourceLint();
        var resources = root.resolve("shared/src/commonMain/composeResources");
        var defaults = lint.catalog(resources.resolve("values"));
        if (defaults.isEmpty()) lint.errors.add("The default strings.xml catalog is empty or missing.");
        if (Files.isDirectory(resources)) {
            try (var directories = Files.list(resources)) {
                for (var directory : directories.filter(Files::isDirectory).sorted().toList()) {
                    if (!directory.getFileName().toString().startsWith("values-")) continue;
                    var translations = lint.catalog(directory);
                    for (var entry : translations.entrySet()) {
                        var original = defaults.get(entry.getKey());
                        if (original == null) {
                            lint.errors.add(directory + ": Unknown translation key " + entry.getKey());
                        } else if (!original.equals(entry.getValue())) {
                            lint.errors.add(directory + ": Placeholder or resource-type mismatch for " + entry.getKey());
                        }
                    }
                }
            }
        }
        for (var module : List.of("shared", "desktopApp", "webApp")) {
            var sources = root.resolve(module + "/src");
            if (!Files.isDirectory(sources)) continue;
            try (var files = Files.walk(sources)) {
                for (var file : files.filter(path -> path.toString().endsWith(".kt")).sorted().toList()) {
                    var relative = root.relativize(file).toString().replace('\\', '/');
                    if (relative.contains("Test/") || relative.contains("/test/")) continue;
                    lint.source(file, relative, defaults.keySet());
                }
            }
        }
        if (!lint.errors.isEmpty()) {
            lint.errors.forEach(System.err::println);
            System.err.println("Resource lint failed: " + lint.errors.size() + " problem(s).");
            System.exit(1);
        }
        System.out.println("Resource lint passed: " + defaults.size() + " resources, " + lint.sourceCount + " Kotlin files.");
    }

    private record Signature(String type, Set<Integer> arguments) {}

    private Map<String, Signature> catalog(Path directory) throws Exception {
        var result = new LinkedHashMap<String, Signature>();
        if (!Files.isDirectory(directory)) return result;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var files = Files.list(directory)) {
            for (var file : files.filter(path -> path.toString().endsWith(".xml")).sorted().toList()) {
                var document = factory.newDocumentBuilder().parse(file.toFile());
                if (!document.getDocumentElement().getTagName().equals("resources")) {
                    errors.add(file + ": Resource XML needs a resources root element.");
                }
                var children = document.getDocumentElement().getChildNodes();
                for (int index = 0; index < children.getLength(); index++) {
                    if (!(children.item(index) instanceof Element element)) continue;
                    var type = element.getTagName();
                    if (!Set.of("string", "plurals", "string-array").contains(type)) {
                        errors.add(file + ": Unsupported resource element " + type);
                        continue;
                    }
                    var name = element.getAttribute("name");
                    if (!name.matches("[a-z][a-z0-9_]*")) errors.add(file + ": Invalid resource name " + name);
                    var arguments = new HashSet<Integer>();
                    if (type.equals("plurals")) {
                        var items = element.getElementsByTagName("item");
                        var quantities = new HashSet<String>();
                        for (int item = 0; item < items.getLength(); item++) {
                            var value = (Element) items.item(item);
                            var quantity = value.getAttribute("quantity");
                            if (!Set.of("zero", "one", "two", "few", "many", "other").contains(quantity) || !quantities.add(quantity)) {
                                errors.add(file + ": Invalid or duplicate plural quantity for " + name);
                            }
                            arguments.addAll(placeholders(file, name, value.getTextContent()));
                        }
                        if (!quantities.contains("other")) errors.add(file + ": Plural " + name + " needs an other quantity.");
                    } else {
                        arguments.addAll(placeholders(file, name, element.getTextContent()));
                    }
                    if (result.putIfAbsent(name, new Signature(type, arguments)) != null) {
                        errors.add(file + ": Duplicate resource " + name);
                    }
                }
            }
        }
        return result;
    }

    private Set<Integer> placeholders(Path file, String name, String text) {
        var result = new HashSet<Integer>();
        var matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            int position = Integer.parseInt(matcher.group(1));
            if (position < 1) errors.add(file + ": Placeholder positions start at 1 in " + name);
            result.add(position);
        }
        if (UNSUPPORTED_PLACEHOLDER.matcher(matcher.replaceAll("")).find()) {
            errors.add(file + ": Use numbered %1$s or %1$d placeholders in " + name);
        }
        for (int position = 1; position <= result.stream().mapToInt(Integer::intValue).max().orElse(0); position++) {
            if (!result.contains(position)) errors.add(file + ": Missing placeholder position " + position + " in " + name);
        }
        return result;
    }

    private void source(Path file, String relative, Set<String> resourceNames) throws Exception {
        sourceCount++;
        var source = Files.readString(file);
        var lexer = new KotlinStrings(source);
        lexer.scan(0, false);
        var references = Pattern.compile("\\b(?:Strings\\.|Res\\.string\\.)([a-zA-Z_][a-zA-Z_0-9]*)").matcher(lexer.code.toString());
        while (references.find()) {
            if (!resourceNames.contains(references.group(1))) {
                errors.add(relative + ":" + line(source, references.start()) + ": Missing resource " + references.group(1));
            }
        }
        boolean userInterface = relative.contains("/ui/") && !relative.contains("/ui/icons/")
            && !relative.endsWith("DrawToolkitSymbols.kt") && !relative.contains("/ui/theme/");
        userInterface |= relative.endsWith("/App.kt");
        if (!userInterface) return;
        var tagRanges = tagRanges(lexer.code.toString());
        tagRanges.addAll(identifierArgumentRanges(lexer.code.toString()));
        for (var token : lexer.strings) {
            if (tagRanges.stream().anyMatch(range -> token.start >= range[0] && token.start < range[1])) continue;
            var text = token.text;
            int start = source.lastIndexOf('\n', token.start) + 1;
            int end = source.indexOf('\n', token.end);
            var context = source.substring(start, end < 0 ? source.length() : end);
            if (context.matches(".*\\b(?:log|logger|debugLog)\\.(?:event|failure)\\(.*")) continue;
            if (context.contains("PopupTooltipScope(") || context.contains("LocalTooltipDismissals.current.register(") || context.contains("registerPopup(") || context.contains("popupOpened(") || context.contains("register(kind")) continue;
            var before = source.substring(start, token.start);
            boolean visibleCall = before.matches(".*\\b(?:Text|TooltipIconButton|HoverTooltip|RibbonCommand|ToolKit|ReferenceToolKit)\\(\\s*$")
                || before.matches(".*\\b(?:contentDescription|title|label|message)\\s*=\\s*$");
            if ((text.matches("[A-Z_]+") && !visibleCall) || text.matches("(?:https?://|application/|image/).*")) continue;
            if (text.matches("[a-z0-9_.-]+") && text.contains("-")) continue;
            if (text.startsWith("{\\\\displaystyle") || text.matches("\\\\u[0-9A-Fa-f]{4}")) continue;
            boolean readable = Pattern.compile("[A-Z][a-z]|\\p{L}.*\\s.*\\p{L}").matcher(text).find();
            if ((readable || visibleCall) && Pattern.compile("\\p{L}").matcher(text).find()) {
                errors.add(relative + ":" + line(source, token.start) + ": Hardcoded UI text: " + text.replace('\n', ' '));
            }
        }
    }

    private static int line(String text, int offset) { return 1 + (int) text.substring(0, offset).chars().filter(c -> c == '\n').count(); }

    private static List<int[]> tagRanges(String code) {
        var result = new ArrayList<int[]>();
        var objects = Pattern.compile("\\bobject\\s+\\w*Tags\\s*\\{").matcher(code);
        while (objects.find()) {
            int depth = 1, end = objects.end();
            while (end < code.length() && depth > 0) {
                char character = code.charAt(end++);
                if (character == '{') depth++;
                if (character == '}') depth--;
            }
            result.add(new int[] {objects.start(), end});
        }
        return result;
    }

    private static List<int[]> identifierArgumentRanges(String code) {
        var result = new ArrayList<int[]>();
        var calls = Pattern.compile("\\bcreateCustomCursor\\s*\\(").matcher(code);
        while (calls.find()) {
            int depth = 1, end = calls.end();
            while (end < code.length() && depth > 0) {
                char character = code.charAt(end++);
                if (character == '(') depth++;
                if (character == ')') depth--;
            }
            result.add(new int[] {calls.start(), end});
        }
        return result;
    }

    private record Token(int start, int end, String text) {}

    /** Kotlin string templates contain nested Kotlin expressions and quoted strings. */
    private static final class KotlinStrings {
        final String source;
        final StringBuilder code;
        final List<Token> strings = new ArrayList<>();
        KotlinStrings(String source) { this.source = source; this.code = new StringBuilder(source); }
        void blank(int start, int end) { for (int i = start; i < end; i++) if (source.charAt(i) != '\n') code.setCharAt(i, ' '); }
        int scan(int position, boolean interpolation) {
            int depth = 1;
            while (position < source.length()) {
                int start = position;
                if (source.startsWith("//", position)) {
                    int end = source.indexOf('\n', position);
                    position = end < 0 ? source.length() : end;
                    blank(start, position);
                } else if (source.startsWith("/*", position)) {
                    int nested = 1; position += 2;
                    while (position < source.length() && nested > 0) {
                        if (source.startsWith("/*", position)) { nested++; position += 2; }
                        else if (source.startsWith("*/", position)) { nested--; position += 2; }
                        else position++;
                    }
                    blank(start, position);
                } else if (source.charAt(position) == '"') position = string(position);
                else if (source.charAt(position) == '\'') {
                    position++;
                    while (position < source.length()) {
                        char character = source.charAt(position++);
                        if (character == '\\') position++;
                        else if (character == '\'') break;
                    }
                    blank(start, Math.min(position, source.length()));
                } else if (interpolation && source.charAt(position) == '{') { depth++; position++; }
                else if (interpolation && source.charAt(position) == '}') {
                    if (--depth == 0) return position;
                    position++;
                } else position++;
            }
            return position;
        }
        int string(int start) {
            boolean triple = source.startsWith("\"\"\"", start);
            String delimiter = triple ? "\"\"\"" : "\"";
            int position = start + delimiter.length();
            var text = new StringBuilder();
            var expressions = new ArrayList<Token>();
            while (position < source.length()) {
                if (source.startsWith(delimiter, position)) {
                    int end = position + delimiter.length();
                    strings.add(new Token(start, end, text.toString()));
                    blank(start, end);
                    expressions.forEach(expression -> code.replace(expression.start, expression.end, expression.text));
                    return end;
                }
                if (!triple && source.charAt(position) == '\\' && position + 1 < source.length()) {
                    text.append(source, position, position + 2); position += 2;
                } else if (source.startsWith("${", position)) {
                    int expressionStart = position + 2;
                    position = scan(expressionStart, true);
                    expressions.add(new Token(expressionStart, position, code.substring(expressionStart, position)));
                    position++;
                } else if (source.charAt(position) == '$' && position + 1 < source.length()
                    && Character.isJavaIdentifierStart(source.charAt(position + 1))) {
                    position += 2;
                    while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) position++;
                } else { text.append(source.charAt(position++)); }
            }
            return position;
        }
    }
}
