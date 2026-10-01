package io.github.louaychayeb.devdesk.daemon;

/** Entry point of the {@code devdeskd} background daemon. */
public final class Main {

    private Main() {
        // Only static methods here, so nobody should create a Main object.
    }

    public static void main(String[] args) {
        System.out.println(greeting());
    }

    // Separate from main() so a test can check the text without capturing System.out.
    static String greeting() {
        return "devdesk daemon: hello (nothing to do yet)";
    }
}
