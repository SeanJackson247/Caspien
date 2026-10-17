import java.io.InputStream;

/** Runnable that discards a child process's stdout (see Compiler.drain). */
public class CompilerDrain implements Runnable {
    private final InputStream in;

    CompilerDrain(InputStream in) {
        this.in = in;
    }

    @Override
    public void run() {
        Compiler.drain(in);
    }
}
