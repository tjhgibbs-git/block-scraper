package dev.mcshot.agent;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Java agent that hot-swaps classes from class output directories into the running JVM.
 *
 * <p>At startup it hashes every {@code .class} file under the watched directories. {@link #reload()} rehashes them
 * and redefines every loaded class whose file changed, in one atomic {@link Instrumentation#redefineClasses} call.
 * Classes that are not loaded yet need nothing: the class loader reads the new file when it first loads them.
 *
 * <p>Code in the game is loaded by a different class loader than this agent, so callers reach it by reflection
 * through the system class loader, and the API only uses JDK types.
 */
public final class HotSwapAgent {
	/** Path-separated list of class directories to watch. Defaults to the directories on the class path. */
	public static final String CLASS_DIRS_PROPERTY = "mcshot.classDirs";

	private static volatile Instrumentation instrumentation;
	/** Absolute class file path to its class name and content hash. */
	private static final Map<Path, ClassFile> baseline = new HashMap<>();
	private static final List<Path> dirs = new ArrayList<>();

	private HotSwapAgent() {
	}

	public static void premain(String args, Instrumentation inst) {
		init(inst);
	}

	public static void agentmain(String args, Instrumentation inst) {
		init(inst);
	}

	private static synchronized void init(Instrumentation inst) {
		instrumentation = inst;
		dirs.clear();
		dirs.addAll(discoverClassDirs());
		baseline.clear();
		baseline.putAll(hashAll(dirs));
	}

	public static boolean isInstalled() {
		return instrumentation != null;
	}

	/** True when the JVM accepts adding/removing methods and fields (JetBrains Runtime with the flag below). */
	public static boolean enhancedRedefinition() {
		return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+AllowEnhancedClassRedefinition");
	}

	public static synchronized List<String> watchedDirs() {
		return dirs.stream().map(Path::toString).toList();
	}

	/**
	 * Redefines every loaded class whose class file changed since startup or the last successful reload.
	 *
	 * @return {@code redefined}: classes swapped in; {@code pending}: changed files whose classes are not loaded yet;
	 *     {@code error}: empty on success, otherwise one message saying why the JVM refused (nothing was changed)
	 */
	public static synchronized Map<String, List<String>> reload() {
		Instrumentation inst = instrumentation;
		if (inst == null) {
			throw new IllegalStateException("mcshot agent is not installed: start the JVM with -javaagent:mcshot-agent.jar");
		}

		Map<Path, ClassFile> current = hashAll(dirs);
		Map<String, Path> changed = new LinkedHashMap<>();
		for (Map.Entry<Path, ClassFile> e : current.entrySet()) {
			if (!e.getValue().equals(baseline.get(e.getKey()))) {
				changed.putIfAbsent(e.getValue().name(), e.getKey());
			}
		}

		Map<String, List<Class<?>>> loaded = new HashMap<>();
		if (!changed.isEmpty()) {
			for (Class<?> c : inst.getAllLoadedClasses()) {
				if (changed.containsKey(c.getName()) && inst.isModifiableClass(c)) {
					loaded.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
				}
			}
		}

		List<ClassDefinition> definitions = new ArrayList<>();
		Set<String> redefined = new LinkedHashSet<>();
		List<String> pending = new ArrayList<>();
		for (Map.Entry<String, Path> e : changed.entrySet()) {
			List<Class<?>> classes = loaded.get(e.getKey());
			if (classes == null) {
				pending.add(e.getKey());
				continue;
			}
			byte[] bytes = read(e.getValue());
			for (Class<?> c : classes) {
				definitions.add(new ClassDefinition(c, bytes));
			}
			redefined.add(e.getKey());
		}

		Map<String, List<String>> result = new LinkedHashMap<>();
		result.put("redefined", new ArrayList<>(redefined));
		result.put("pending", pending);
		try {
			if (!definitions.isEmpty()) {
				inst.redefineClasses(definitions.toArray(ClassDefinition[]::new));
			}
		} catch (UnsupportedOperationException | UnmodifiableClassException
				| ClassNotFoundException | LinkageError e) {
			String why = e instanceof UnsupportedOperationException && !enhancedRedefinition()
					? " (this JVM only swaps method bodies; adding or removing methods, fields or supertypes needs a"
							+ " restart, or JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition)"
					: "";
			result.put("redefined", List.of());
			result.put("error", List.of("restart required: " + e.getClass().getSimpleName() + ": " + e.getMessage() + why));
			return result;
		}
		baseline.clear();
		baseline.putAll(current);
		result.put("error", List.of());
		return result;
	}

	static List<Path> discoverClassDirs() {
		String explicit = System.getProperty(CLASS_DIRS_PROPERTY);
		Set<Path> found = new LinkedHashSet<>();
		List<String> entries = new ArrayList<>();
		if (explicit != null && !explicit.isBlank()) {
			entries.addAll(Arrays.asList(explicit.split(File.pathSeparator)));
		} else {
			entries.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
			// Fabric Loader's dev launch lists each mod's output dirs here, as "a:b;c:d" (groups split by ';').
			String groups = System.getProperty("fabric.classPathGroups");
			if (groups != null) {
				for (String group : groups.split(";")) {
					entries.addAll(Arrays.asList(group.split(File.pathSeparator)));
				}
			}
		}
		for (String entry : entries) {
			if (entry.isBlank()) {
				continue;
			}
			Path p = Path.of(entry).toAbsolutePath().normalize();
			if (Files.isDirectory(p)) {
				found.add(p);
			}
		}
		return new ArrayList<>(found);
	}

	private record ClassFile(String name, String hash) {
	}

	private static Map<Path, ClassFile> hashAll(List<Path> roots) {
		Map<Path, ClassFile> out = new HashMap<>();
		for (Path root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(root)) {
				files.filter(f -> f.toString().endsWith(".class") && Files.isRegularFile(f))
						.forEach(f -> out.put(f, new ClassFile(className(root.relativize(f)), hash(read(f)))));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return out;
	}

	private static String className(Path relative) {
		String s = relative.toString().replace(File.separatorChar, '/');
		return s.substring(0, s.length() - ".class".length()).replace('/', '.');
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
