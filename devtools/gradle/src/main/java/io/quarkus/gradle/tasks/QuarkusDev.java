/*
 * Copyright 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.quarkus.gradle.tasks;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.AppModelResolver;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.deployment.ApplicationInfoUtil;
import io.quarkus.dev.DevModeContext;
import io.quarkus.dev.DevModeMain;
import io.quarkus.gradle.QuarkusPluginExtension;

/**
 * @author <a href="mailto:stalep@gmail.com">Ståle Pedersen</a>
 */
public class QuarkusDev extends QuarkusTask {

    private Set<File> filesIncludedInClasspath = new HashSet<>();

    private String debug;

    private File buildDir;

    private String sourceDir;

    private String jvmArgs;

    private boolean preventnoverify = false;

    public QuarkusDev() {
        super("Development mode: enables hot deployment with background compilation");
    }

    @Optional
    @Input
    public String getDebug() {
        return debug;
    }

    @Option(description = "If this server should be started in debug mode. " +
            "The default is to start in debug mode without suspending and listen on port 5005." +
            " It supports the following options:\n" +
            " \"false\" - The JVM is not started in debug mode\n" +
            " \"true\" - The JVM is started in debug mode and suspends until a debugger is attached to port 5005\n" +
            " \"client\" - The JVM is started in client mode, and attempts to connect to localhost:5005\n" +
            "\"{port}\" - The JVM is started in debug mode and suspends until a debugger is attached to {port}", option = "debug")
    public void setDebug(String debug) {
        this.debug = debug;
    }

    @InputDirectory
    @Optional
    public File getBuildDir() {
        if (buildDir == null)
            buildDir = getProject().getBuildDir();
        return buildDir;
    }

    public void setBuildDir(File buildDir) {
        this.buildDir = buildDir;
    }

    @Optional
    @InputDirectory
    public File getSourceDir() {
        if (sourceDir == null)
            return extension().sourceDir();
        else
            return new File(sourceDir);
    }

    @Option(description = "Set source directory", option = "source-dir")
    public void setSourceDir(String sourceDir) {
        this.sourceDir = sourceDir;
    }

    @Optional
    @Input
    public String getJvmArgs() {
        return jvmArgs;
    }

    @Option(description = "Set JVM arguments", option = "jvm-args")
    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    @Optional
    @Input
    public boolean isPreventnoverify() {
        return preventnoverify;
    }

    @Option(description = "value is intended to be set to true when some generated bytecode is" +
            " erroneous causing the JVM to crash when the verify:none option is set " +
            "(which is on by default)", option = "prevent-noverify")
    public void setPreventnoverify(boolean preventnoverify) {
        this.preventnoverify = preventnoverify;
    }

    @TaskAction
    public void startDev() {

        QuarkusPluginExtension extension = (QuarkusPluginExtension) getProject().getExtensions().findByName("quarkus");

        if (!getSourceDir().isDirectory()) {
            throw new GradleException("The `src/main/java` directory is required, please create it.");
        }

        if (!extension().outputDirectory().isDirectory()) {
            throw new GradleException("The project has no output yet, " +
                    "this should not happen as build should have been executed first. " +
                    "Do the project have any source files?");
        }
        DevModeContext context = new DevModeContext();
        try {
            List<String> args = new ArrayList<>();
            args.add(findJavaTool());
            if (getDebug() == null) {
                // debug mode not specified
                // make sure 5005 is not used, we don't want to just fail if something else is using it
                try (Socket socket = new Socket(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 5005)) {
                    System.err.println("Port 5005 in use, not starting in debug mode");
                } catch (IOException e) {
                    args.add("-Xdebug");
                    args.add("-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=n");
                }
            } else if (getDebug().toLowerCase().equals("client")) {
                args.add("-Xdebug");
                args.add("-Xrunjdwp:transport=dt_socket,address=localhost:5005,server=n,suspend=n");
            } else if (getDebug().toLowerCase().equals("true")) {
                args.add("-Xdebug");
                args.add("-Xrunjdwp:transport=dt_socket,address=localhost:5005,server=y,suspend=y");
            } else if (!getDebug().toLowerCase().equals("false")) {
                try {
                    int port = Integer.parseInt(getDebug());
                    if (port <= 0) {
                        throw new GradleException("The specified debug port must be greater than 0");
                    }
                    args.add("-Xdebug");
                    args.add("-Xrunjdwp:transport=dt_socket,address=" + port + ",server=y,suspend=y");
                } catch (NumberFormatException e) {
                    throw new GradleException(
                            "Invalid value for debug parameter: " + getDebug() + " must be true|false|client|{port}");
                }
            }
            if (getJvmArgs() != null) {
                args.addAll(Arrays.asList(getJvmArgs().split(" ")));
            }

            // the following flags reduce startup time and are acceptable only for dev purposes
            args.add("-XX:TieredStopAtLevel=1");
            if (!isPreventnoverify()) {
                args.add("-Xverify:none");
            }

            //build a class-path string for the base platform
            //this stuff does not change
            // Do not include URIs in the manifest, because some JVMs do not like that
            StringBuilder classPathManifest = new StringBuilder();

            final AppModel appModel;
            final AppModelResolver modelResolver = extension().resolveAppModel();
            try {
                final AppArtifact appArtifact = extension.getAppArtifact();
                appArtifact.setPath(extension.outputDirectory().toPath());
                appModel = modelResolver.resolveModel(appArtifact);
            } catch (AppModelResolverException e) {
                throw new GradleException("Failed to resolve application model " + extension.getAppArtifact() + " dependencies",
                        e);
            }
            for (AppDependency appDep : appModel.getAllDependencies()) {
                addToClassPaths(classPathManifest, context, appDep.getArtifact().getPath().toFile());
            }

            args.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
            File wiringClassesDirectory = new File(getBuildDir(), "wiring-classes");
            wiringClassesDirectory.mkdirs();
            addToClassPaths(classPathManifest, context, wiringClassesDirectory);

            //we also want to add the maven plugin jar to the class path
            //this allows us to just directly use classes, without messing around copying them
            //to the runner jar
            addGradlePluginDeps(classPathManifest, context);

            //now we need to build a temporary jar to actually run

            File tempFile = new File(getBuildDir(), extension.finalName() + "-dev.jar");
            tempFile.delete();
            tempFile.deleteOnExit();

            StringBuilder resources = new StringBuilder();
            String res = null;
            for (File file : extension.resourcesDir()) {
                if (resources.length() > 0)
                    resources.append(File.pathSeparator);
                resources.append(file.getAbsolutePath());
                res = file.getAbsolutePath();
            }
            DevModeContext.ModuleInfo moduleInfo = new DevModeContext.ModuleInfo(getProject().getName(),
                    getSourceDir().getAbsolutePath(),
                    extension.outputDirectory().getAbsolutePath(), res);
            context.getModules().add(moduleInfo);

            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tempFile))) {
                out.putNextEntry(new ZipEntry("META-INF/"));
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPathManifest.toString());
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(out);

                out.putNextEntry(new ZipEntry(DevModeMain.DEV_MODE_CONTEXT));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes));
                obj.writeObject(context);
                obj.close();
                out.write(bytes.toByteArray());
            }

            extension.outputDirectory().mkdirs();
            ApplicationInfoUtil.writeApplicationInfoProperties(appModel.getAppArtifact(), extension.outputDirectory().toPath());

            args.add("-jar");
            args.add(tempFile.getAbsolutePath());
            args.add(extension.outputDirectory().getAbsolutePath());
            args.add(wiringClassesDirectory.getAbsolutePath());
            args.add(new File(getBuildDir(), "transformer-cache").getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(args.toArray(new String[0]));
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.directory(extension.outputDirectory());
            System.out.println("Starting process: ");
            pb.command().forEach(System.out::println);
            System.out.println("Args: ");
            args.forEach(System.out::println);

            Process p = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    p.destroy();
                }
            }, "Development Mode Shutdown Hook"));
            try {
                ExecutorService es = Executors.newSingleThreadExecutor();
                es.submit(() -> copyOutputToConsole(p.getInputStream()));

                p.waitFor();
            } catch (Exception e) {
                p.destroy();
                throw e;
            }

        } catch (Exception e) {
            throw new GradleException("Failed to run", e);
        }
    }

    private void copyOutputToConsole(InputStream is) {
        try (InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        } catch (Exception e) {
            throw new GradleException("Failed to copy output to console", e);
        }
    }

    /**
     * Search for the java command in the order:
     * 1. maven-toolchains plugin configuration
     * 2. java.home location
     * 3. java[.exe] on the system path
     *
     * @return the java command to use
     */
    protected String findJavaTool() {
        // use the same JVM as the one used to run Maven (the "java.home" one)
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        File javaCheck = new File(java);
        if (!javaCheck.canExecute()) {

            java = null;
            // Try executable extensions if windows
            if (OS.determineOS() == OS.WINDOWS && System.getenv().containsKey("PATHEXT")) {
                String extpath = System.getenv("PATHEXT");
                String[] exts = extpath.split(";");
                for (String ext : exts) {
                    File winExe = new File(javaCheck.getAbsolutePath() + ext);
                    if (winExe.canExecute()) {
                        java = winExe.getAbsolutePath();
                        break;
                    }
                }
            }
            // Fallback to java on the path
            if (java == null) {
                if (OS.determineOS() == OS.WINDOWS) {
                    java = "java.exe";
                } else {
                    java = "java";
                }
            }
        }
        return java;
    }

    private void addGradlePluginDeps(StringBuilder classPathManifest, DevModeContext context) {
        Configuration conf = getProject().getBuildscript().getConfigurations().getByName("classpath");
        ResolvedDependency quarkusDep = conf.getResolvedConfiguration().getFirstLevelModuleDependencies().stream()
                .filter(rd -> "quarkus-gradle-plugin".equals(rd.getModuleName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable to find quarkus-gradle-plugin dependency"));

        quarkusDep.getAllModuleArtifacts().stream()
                .map(ra -> ra.getFile())
                .forEach(f -> addToClassPaths(classPathManifest, context, f));
    }

    private void addToClassPaths(StringBuilder classPathManifest, DevModeContext context, File file) {
        if (filesIncludedInClasspath.add(file)) {
            getProject().getLogger().info("Adding dependency {}", file);

            URI uri = file.toPath().toAbsolutePath().toUri();
            classPathManifest.append(uri.getPath());
            context.getClassPath().add(toUrl(uri));
            if (file.isDirectory()) {
                classPathManifest.append("/");
            }
            classPathManifest.append(" ");
        }
    }

    private URL toUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Failed to convert URI to URL: " + uri, e);
        }
    }

    /**
     * Enum to classify the os.name system property
     */
    static enum OS {
        WINDOWS,
        LINUX,
        MAC,
        OTHER;

        private String version;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        static OS determineOS() {
            OS os = OS.OTHER;
            String osName = System.getProperty("os.name");
            osName = osName.toLowerCase();
            if (osName.contains("windows")) {
                os = OS.WINDOWS;
            } else if (osName.contains("linux")
                    || osName.contains("freebsd")
                    || osName.contains("unix")
                    || osName.contains("sunos")
                    || osName.contains("solaris")
                    || osName.contains("aix")) {
                os = OS.LINUX;
            } else if (osName.contains("mac os")) {
                os = OS.MAC;
            } else {
                os = OS.OTHER;
            }

            os.setVersion(System.getProperty("os.version"));
            return os;
        }
    }
}
