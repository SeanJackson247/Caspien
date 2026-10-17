import java.net.URL;
import java.net.URLClassLoader;

/**
 * Serves only the caspien.bytecode.* classes (the shared BytecodeProgram, parser and serializer) from one stage's out/, and is the parent
 * of the loaders of the stages that exchange the program object, so they all see the same BytecodeProgram class. Everything else
 * (the JDK, the stage's own classes) is left to the platform loader and the stage's own loader.
 */
final class CompilerSharedLoader extends URLClassLoader {
    private static final String SHARED_PACKAGE = "caspien.bytecode.";

    CompilerSharedLoader(URL classes) {
        super(new URL[]{classes}, ClassLoader.getPlatformClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(SHARED_PACKAGE)) {
            return getParent().loadClass(name);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = findClass(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
