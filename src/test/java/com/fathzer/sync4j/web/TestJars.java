package com.fathzer.sync4j.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Builds plugin jars at test time: a jar that contains classes compiled from sources, and the declaration of the services they implement.
 */
public final class TestJars {
    private TestJars() {
        // Utility class
    }

    /**
     * Builds a jar.
     * @param jar the jar file to create
     * @param sources the sources of the classes (class name -> java source)
     * @param services the services (full name of the interface -> names of the implementation classes)
     * @throws IOException if the sources can't be compiled or the jar can't be written
     */
    public static void build(Path jar, Map<String, String> sources, Map<String, List<String>> services) throws IOException {
        final Path work = Files.createTempDirectory(jar.getParent(), "build");
        try {
            final Path src = Files.createDirectory(work.resolve("src"));
            final Path classes = Files.createDirectory(work.resolve("classes"));
            final java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of("-d", classes.toString(), "-cp", System.getProperty("java.class.path")));
            for (Map.Entry<String, String> source : sources.entrySet()) {
                final Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (!sources.isEmpty() && compiler.run(null, null, null, args.toArray(new String[0])) != 0) {
                throw new IOException("The test plugin can't be compiled");
            }
            try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
                try (Stream<Path> files = Files.walk(classes)) {
                    for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        add(jarOut, classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                    }
                }
                for (Map.Entry<String, List<String>> service : services.entrySet()) {
                    add(jarOut, "META-INF/services/" + service.getKey(), String.join("\n", service.getValue()).getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void add(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
