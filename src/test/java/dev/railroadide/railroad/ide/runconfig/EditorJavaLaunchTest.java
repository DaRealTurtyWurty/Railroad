package dev.railroadide.railroad.ide.runconfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EditorJavaLaunchTest {
    @Test
    public void findsDeclaredEntryPointsAndUsesBinaryNames() {
        assertEquals(
            List.of(new EditorJavaLaunch(3, "sample.App", null), new EditorJavaLaunch(6, "sample.App$Nested", null)),
            EditorJavaLaunch.find("""
                package sample;
                class App {
                    public static void main(java.lang.String... args) {}
                    String example = "void main()";
                    static class Nested {
                        void main() {}
                    }
                    private static void main() {}
                    static void main(int[] args) {}
                }
                """, "App.java"));
    }

    @Test
    public void discoversImportedAndQualifiedTestsAndKeepsTheirDeclaringClass() {
        List<EditorJavaLaunch> tests = EditorJavaLaunch.find("""
            package p;
            import org.junit.jupiter.api.*;
            import org.junit.jupiter.params.ParameterizedTest;
            class Suite {
                @Test void first() {}
                @Nested class Inner {
                    @ParameterizedTest void samples(int value) {}
                    @org.junit.Test void legacy() {}
                    @example.Test void unrelated() {}
                }
            }
            """, "Suite.java");
        assertEquals(List.of(new EditorJavaLaunch(5, "p.Suite", "first"),
            new EditorJavaLaunch(7, "p.Suite$Inner", "samples"),
            new EditorJavaLaunch(8, "p.Suite$Inner", "legacy")), tests);
        assertEquals(List.of(),
            EditorJavaLaunch.find("import example.Test; class Other { @Test void f() {} }", "Other.java"));
    }

    @Test
    public void mainSignatureUsesDeclarationTokensAndPhysicalNameLines() {
        assertEquals(List.of(new EditorJavaLaunch(4, "p.One", null), new EditorJavaLaunch(6, "p.Two", null)),
            EditorJavaLaunch.find("""
                package p;
                class One {
                    static void
                        main(final String args[]) { class Local { void main() {} } }
                }
                record Two(int i) { void main(Two this, java.lang.String[] args) {} }
                interface Three { abstract void main(); }
                class Four {
                    void main(String[][] args) {}
                    int main() { return 1; }
                    Object nested = new Object() { public void main() {} };
                }
                """.replace("\n", "\r\n"), "One.java"));
    }

    @Test
    public void explicitAnnotationImportShadowsWildcardAndUnicodeNamesAreDecoded() {
        assertEquals(List.of(new EditorJavaLaunch(4, "Suite", "record")),
            EditorJavaLaunch.find("""
                import org.junit.jupiter.api.*;
                import other.Test;
                class Suite {
                    @org.junit.jupiter.api.Test void \\u0072ecord() {}
                    @Test void helper() {}
                }
                """, "Suite.java"));
    }

    @Test
    public void compactSourceUsesItsFilename() {
        assertEquals(List.of(new EditorJavaLaunch(1, "Example", null)),
            EditorJavaLaunch.find("void main() {}", "Example.java"));
    }
}
