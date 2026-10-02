package io.github.amichne.kast.cli.rpc;

/** Disposable process for OS incarnation and retirement tests; it performs no semantic work. */
public final class KastToolRpcMain {
    public static void main(String[] arguments) throws Exception {
        System.out.println("ready");
        System.out.flush();
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
    }
}
