package gr.gousiosg.javacg.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

/** A three-module project: a, b (depends on a, one failing and one erroring test), c (depends on b, compile error). */
final class MavenProject {

    private MavenProject() {
    }

    static Path create(Path dir) throws Exception {
        write(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>x</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <modules><module>a</module><module>b</module><module>c</module></modules>
                <dependencyManagement><dependencies><dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId>
                <version>6.1.3</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
                <build><pluginManagement><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.6.1</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.5.1</version></plugin>
                </plugins></pluginManagement></build></project>
                """);
        module(dir, "a", "");
        module(dir, "b", "<dependency><groupId>x</groupId><artifactId>a</artifactId><version>1</version></dependency>"
                + "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>");
        module(dir, "c", "<dependency><groupId>x</groupId><artifactId>b</artifactId><version>1</version></dependency>");
        write(dir.resolve("a/src/main/java/a/A.java"), "package a; public class A { public static int two() { return 2; } }\n");
        write(dir.resolve("b/src/main/java/b/B.java"), "package b; public class B { public static int four() { return a.A.two() * 2; } }\n");
        write(dir.resolve("b/src/test/java/b/BTest.java"), """
                package b;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import org.junit.jupiter.api.Test;
                class BTest {
                    @Test void four() { assertEquals(4, B.four()); }
                    @Test void five() { assertEquals(5, B.four(), "math"); }
                    @Test void npe() { Object o = null; o.toString(); }
                }
                """);
        write(dir.resolve("c/src/main/java/c/C.java"), "package c; public class C { int x = b.B.four() + missing(); }\n");
        return dir;
    }

    private static void module(Path root, String name, String deps) throws Exception {
        write(root.resolve(name).resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent>
                <artifactId>%s</artifactId><dependencies>%s</dependencies></project>
                """.formatted(name, deps));
    }

    private static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }
}
