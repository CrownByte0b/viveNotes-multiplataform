# ByteInk

### Build the 3 libs

```bash
native/build-all.sh
./gradlew build
./gradlew -PbyteinkWasm=true publishToMavenLocal
```

---

or individually All are needed

### wasm lib

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
native/setup-wasm.sh
native/build-wasm.sh
./gradlew -PbyteinkWasm=true publishToMavenLocal
```

### Linux and Windows

```bash
native/build-linux.sh
native/build-windows.sh
./gradlew publishToMavenLocal
```

# libvive_converter

`libvive_converter.so` and `libvive_converter.dll`
