package dev.mcshot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs a child JVM with the agent, edits a class on disk, and checks the running code picks it up. */
class HotSwapAgentTest {
	// The driver reads "reload" commands on stdin and prints the agent's report, then what Greeter says now.
	private static final String MAIN = """
			package demo;
			public class Main {
				public static void main(String[] args) throws Exception {
					var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
					System.out.println("greet=" + new Greeter().greet());
					Class<?> agent = ClassLoader.getSystemClassLoader().loadClass("dev.mcshot.agent.HotSwapAgent");
					String line;
					while ((line = in.readLine()) != null) {
						System.out.println("report=" + agent.getMethod("reload").invoke(null));
						System.out.println("greet=" + new Greeter().greet());
					}
				}
			}
			""";

	@TempDir
	Path dir;
	private Path src;
	private Path classes;
	private Process process;
	private BufferedReader out;
	private Writer in;

	@BeforeEach
	void setUp() throws IOException {
		src = Files.createDirectories(dir.resolve("src/demo"));
		classes = Files.createDirectories(dir.resolve("classes"));
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if (process != null) {
			process.destroy();
			process.waitFor(5, TimeUnit.SECONDS);
		}
	}

	private void compile(String greeterBody) throws IOException {
		Files.writeString(src.resolve("Main.java"), MAIN);
		Files.writeString(src.resolve("Greeter.java"), "package demo;\npublic class Greeter {\n" + greeterBody + "\n}\n");
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		int rc = javac.run(null, null, null, "-d", classes.toString(),
				src.resolve("Main.java").toString(), src.resolve("Greeter.java").toString());
		assertEquals(0, rc, "javac failed");
	}

	private void launch() throws IOException {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> cmd = new ArrayList<>(List.of(java, "-javaagent:" + System.getProperty("mcshot.agentJar"),
				"-cp", classes.toString(), "demo.Main"));
		process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
	}

	private String next() throws IOException {
		String line = out.readLine();
		while (line != null && line.startsWith("Picked up JAVA_TOOL_OPTIONS")) {
			line = out.readLine();
		}
		return line;
	}

	private String reload() throws IOException {
		in.write("reload\n");
		in.flush();
		return next();
	}

	@Test
	void swapsMethodBodiesOfLoadedClasses() throws IOException {
		compile("public String greet() { return \"v1\"; }");
		launch();
		assertEquals("greet=v1", next());

		compile("public String greet() { return \"v2\"; }");
		assertEquals("report={redefined=[demo.Greeter], pending=[], error=[]}", reload());
		assertEquals("greet=v2", next());

		// Nothing changed since the last reload: nothing to do.
		assertEquals("report={redefined=[], pending=[], error=[]}", reload());
		assertEquals("greet=v2", next());
	}

	@Test
	void refusesSchemaChangesWithRestartRequired() throws IOException {
		compile("public String greet() { return \"v1\"; }");
		launch();
		assertEquals("greet=v1", next());

		compile("public String greet() { return extra(); }\nprivate String extra() { return \"v2\"; }");
		String report = reload();
		if (HotSwapAgent.enhancedRedefinition()) {
			return; // JetBrains Runtime accepts this; nothing to check
		}
		assertTrue(report.startsWith("report={redefined=[], pending=[], error=[restart required: "), report);
		assertTrue(report.contains("JetBrains Runtime"), report);
		assertEquals("greet=v1", next(), "a refused reload must leave the old code running");
	}
}
