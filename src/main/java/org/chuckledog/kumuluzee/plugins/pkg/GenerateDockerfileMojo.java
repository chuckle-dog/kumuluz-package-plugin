package org.chuckledog.kumuluzee.plugins.pkg;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

@Mojo(name = "generate-dockerfile", defaultPhase = LifecyclePhase.PACKAGE /*, defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME*/)
public class GenerateDockerfileMojo extends AbstractMojo {

    private static final Logger log = Logger.getLogger(GenerateDockerfileMojo.class.getName());

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}")
    private File outputDirectory;

    @Parameter( defaultValue = "${project.resources}", required = true, readonly = true )
    private List<Resource> resources;

    @Parameter(defaultValue = "uber", required = true, readonly = true)
    private String packagingType;

    private static final String DOCKERFILE_SKIMMED_TEMPLATE = "dockerfileSkimmed.mustache";
    private static final String DOCKERFILE_UBER_TEMPLATE = "dockerfileUber.mustache";
    private static final String DOCKERIGNORE_TEMPLATE = "dockerignore.mustache";

    private boolean generateDockerfile = true;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        List<String> modules = project.getModules();

        List<Dependency> dependencies = project.getDependencies();

        LinkedList<String> moduleFileNames = new LinkedList<>();

        HashMap<String, Object> mustacheContext = new HashMap<>();

        mustacheContext.put("port", readPortFromConfig());

        String dockerfileTemplate = DOCKERFILE_UBER_TEMPLATE;

        String executableName = String.format("%s-%s.jar", project.getName(), project.getVersion());

        if (packagingType.equals("skimmed")){
            dockerfileTemplate = DOCKERFILE_SKIMMED_TEMPLATE;
            executableName = executableName.replace(".jar", "-skimmed.jar");
        }
        else if (packagingType.equals("exploded")){
        }

        mustacheContext.put("executableName", executableName);

        // Single-module project
        if (project.getParent() == null && modules.size() == 0) {
            mustacheContext.put("name", project.getName());
            mustacheContext.put("description", project.getDescription());

            writeFileFromTemplate(dockerfileTemplate, "Dockerfile", mustacheContext);
        }
        // Multi-module project
        else if (project.getParent() != null && modules.size() == 0){

            for (Dependency dependency : dependencies){
                // Find module with core component
                if (dependency.getArtifactId().contains("kumuluzee-core")){

                    // Collect module JAR names
                    MavenProject parent = project.getParent();
                    if (parent != null){
                        for (Object module : parent.getModules()){
                            if (!project.getArtifactId().equals(module)) {
                                String moduleFileName = String.format("%s-%s.jar", module.toString(), parent.getVersion());
                                moduleFileNames.add(moduleFileName);
                            }
                        }

                        mustacheContext.put("modules", moduleFileNames);
                    }

                    for (Object resource : project.getResources()){
                        getLog().info(resource.toString());
                    }

                    copySkimmedModules(moduleFileNames);

                    mustacheContext.put("name", parent.getName());
                    mustacheContext.put("description", parent.getDescription());

                    writeFileFromTemplate(dockerfileTemplate, "Dockerfile", mustacheContext);
                    writeFileFromTemplate(DOCKERIGNORE_TEMPLATE, ".dockerignore", mustacheContext);

                    break;
                }
            }
        }

    }

    private List<String> getProjectDependencyNames(List<Artifact> artifacts){

        List<String> dependencyNames = new LinkedList<>();

        for (Artifact artifact : artifacts){
            dependencyNames.add(artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar");
        }

        return dependencyNames;
    }

    private String getDependencyFileName(Artifact artifact){
        return (String.format("%s-%s.jar", artifact.getArtifactId(), artifact.getVersion()));
    }

    private boolean containsArtifactFile(Artifact artifact, List<File> fileList){
        for (File file : fileList){
            if (file.getName().matches(getDependencyFileName(artifact))){
                return true;
            }
        }
        return false;
    }

    private void fetchMissingDependencies(List<Artifact> missingArtifacts){
        missingArtifacts.get(0).getFile().getName();
    }

    private String readPortFromConfig(){

        BufferedReader reader;
        try {
            reader = new BufferedReader(new FileReader( resources.get(0).getDirectory() + "/config.yaml"));

            String line = reader.readLine();
            while (line != null){
                if (line.contains("port:")){
                    return line.substring(line.indexOf("port:") + 5).trim();
                }
                line = reader.readLine();
            }
        }
        catch (IOException e){
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Writes a file in the output directory with the given template and data
     *
     * @param templateFile name of template file to use (located in the resources directory)
     * @param finalName final name of written file (will be created in the target directory)
     * @param mustacheContext a map of data for the mustache compiler to use in the templates
     */
    private void writeFileFromTemplate(String templateFile, String finalName, HashMap<String, Object> mustacheContext){
        log.info("Writing file: " + finalName);

        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache m = mf.compile(templateFile);

        StringWriter writer = new StringWriter();

        try {
            m.execute(writer, mustacheContext).flush();
            FileWriter fileWriter = new FileWriter(outputDirectory.getAbsolutePath() + File.separator + finalName);

            fileWriter.write(writer.toString());
            fileWriter.close();

            getLog().info("Writing successful.");
        }
        catch (IOException e){
            e.printStackTrace();
            getLog().warn("Error writing file: " + finalName);
        }
    }

    /**
     * To take advantage of docker layering, the module JARs must be kept in a separate directory
     * A copy of the module JARs must remain in the LIB directory for the app to run outside of Docker
     * The copies in the LIB directory are excluded from the Docker build via .dockerignore
     *
     * @param moduleFileNames names of files to copy
     */
    private void copySkimmedModules(List<String> moduleFileNames){

        File modulesDir = new File(outputDirectory.getAbsolutePath() + "/modules");

        if (!modulesDir.exists()){
            if (!modulesDir.mkdir()){
                getLog().warn("Could not create modules directory.");
            }
        }

        for (String moduleFileName : moduleFileNames){
            File moduleJAR = new File(outputDirectory.getAbsolutePath() + "/lib/" + moduleFileName);
            File copiedModuleJAR = new File(outputDirectory.getAbsolutePath() + "/modules/" + moduleFileName);

            try {
                FileUtils.copyFile(moduleJAR, copiedModuleJAR);
            }
            catch (IOException e){
                e.printStackTrace();
            }
        }

    }

}
