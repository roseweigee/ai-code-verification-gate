package dev.aiverify.compile;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles a single Java source in memory and loads it in an isolated class loader.
 * Used for the candidate and for every mutant, so nothing AI-written touches disk classes.
 */
public final class InMemoryCompiler {

    public record Result(boolean ok, List<String> errors, Class<?> loadedClass) {
    }

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    public Result compile(String className, String source) {
        if (compiler == null) {
            throw new IllegalStateException("A JDK (not a JRE) is required: javax.tools compiler not found");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager std = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null);
        Map<String, ByteArrayOutputStream> classBytes = new HashMap<>();

        ForwardingJavaFileManager<StandardJavaFileManager> fm = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                                                       FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        classBytes.put(name, out);
                        return out;
                    }
                };
            }
        };

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        List<String> options = List.of("-classpath", System.getProperty("java.class.path"),
                "--release", "17", "-proc:none", "-Xlint:none");
        boolean ok = compiler.getTask(null, fm, diagnostics, options, null, List.of(src)).call();

        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add("line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
            }
        }
        if (!ok) {
            return new Result(false, errors, null);
        }

        ClassLoader loader = new ClassLoader(InMemoryCompiler.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream out = classBytes.get(name);
                if (out == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] b = out.toByteArray();
                return defineClass(name, b, 0, b.length);
            }
        };
        try {
            return new Result(true, errors, loader.loadClass(className));
        } catch (ClassNotFoundException e) {
            errors.add("Compiled, but class " + className + " was not found. Check the package and class name.");
            return new Result(false, errors, null);
        }
    }
}
