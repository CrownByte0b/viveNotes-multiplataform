# Project memory

Before planning, diagnosing, or modifying this project:

1. Read `memory/initial.md`.
2. Read the files in `memory/` relevant to the current task.
3. Treat these files as persistent project context across sessions.
4. Update the relevant memory file after material decisions or implementation changes.
5. Do not overwrite unrelated notes or assume outdated plans reflect completed work—verify against the codebase.
6. Do not modify docs/ directory unless asked to.

# UI design language

Use Material 3 Expressive as the default design language for all new and modified UI.

- Prefer official Material 3 Expressive components over custom widgets.
- Use Material 3 Expressive motion patterns for transitions, state changes, loading, selection, navigation, and component interactions.
- Keep shapes, typography, color, spacing, and motion consistent with the app’s Material 3 theme.
- Reuse existing themed components and motion tokens instead of introducing one-off styling or animation values.
- When no Expressive component exists, build with standard Material 3 Compose primitives while matching the Expressive visual and motion language.
- Preserve accessibility: respect reduced-motion preferences, maintain readable contrast, provide semantic labels, and keep touch targets appropriately sized.
- Do not introduce legacy Material 2 components.

# Testing

Every feature and bug fix must include automated tests at the appropriate layers.

- A feature is not complete until its tests are written and pass.
- Test domain rules and state transitions with deterministic unit tests.
- Test visible behavior and user interaction with Compose UI tests.
- Test persistence, filesystem, networking, codecs, migrations, and platform adapters with focused
  integration or contract tests using realistic fixtures.
- Add regression tests before or with every bug fix.
- Preserve and port applicable Android tests as compatibility specifications; do not weaken or
  delete a test merely to make a port pass.
- Run the relevant focused tests while developing and the full JVM suite before handing work off.
