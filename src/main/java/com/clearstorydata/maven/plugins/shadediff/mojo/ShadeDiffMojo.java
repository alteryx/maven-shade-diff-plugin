package com.clearstorydata.maven.plugins.shadediff.mojo;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.*;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.plugins.shade.mojo.ArtifactSet;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import com.google.common.base.Joiner;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads the specified shaded jar and adds all artifacts included into that jar
 * with versions different from those required by the current project to shade plugin
 * exclusions.
 * <p/>
 * Technique used for resolving artifact:
 * http://stackoverflow.com/questions/1440224/how-can-i-download-maven-artifacts-within-a-plugin
 */
@Mojo(name = "shade-diff", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true,
  requiresDependencyResolution = ResolutionScope.RUNTIME)
public class ShadeDiffMojo extends AbstractMojo {

  /**
   * Used to look up Artifacts in the remote repository.
   */
  @Component
  protected ArtifactFactory factory;

  /**
   * Used to look up Artifacts in the remote repository.
   */
  @Component
  protected ArtifactResolver artifactResolver;

  /**
   * List of Remote Repositories used by the resolver
   */
  @Parameter(defaultValue = "${project.remoteArtifactRepositories}",
    required = true, readonly = true)
  protected List<ArtifactRepository> remoteRepositories;

  /**
   * Location of the local repository.
   */
  @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
  protected ArtifactRepository localRepository;

  @Parameter
  private ArtifactSet artifactSet;

  @Component
  private MavenProject project;

  /**
   * Location of the file.
   */
  @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
  private File outputDirectory;

  @Parameter
  private ShadedJarExclusion[] excludeShadedJars;

  private static String getGroupArtifactType(Artifact a) {
    return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getType();
  }

  private Plugin lookupPlugin(String key) {

    List<Plugin> plugins = project.getBuildPlugins();

    for (Iterator<Plugin> iterator = plugins.iterator(); iterator.hasNext(); ) {
      Plugin plugin = iterator.next();
      getLog().info("plugin: " + plugin.getKey());
      if (key.equalsIgnoreCase(plugin.getKey())) {
        return plugin;
      }
    }
    return null;
  }


  public void execute()
    throws MojoExecutionException {

    try {

      Plugin shadePlugin = lookupPlugin("org.apache.maven.plugins:maven-shade-plugin");
      if (shadePlugin == null) {
        throw new MojoExecutionException("maven-shade-plugin not found");
      }

      Map<String, String> groupArtifactTypeToVersion = new HashMap<String, String>();

      for (Artifact artifact : project.getArtifacts()) {
        getLog().info("dependency: " + artifact.getId());
        groupArtifactTypeToVersion.put(getGroupArtifactType(artifact), artifact.getVersion());
      }

      getLog().info("excluded shaded jars: " + excludeShadedJars.length);

      List<String> excludes = new ArrayList<String>();

      for (ShadedJarExclusion e : excludeShadedJars) {
        getLog().info("groupId=" + e.getGroupId() + ", " +
          "artifactId=" + e.getArtifactId() + ", " +
          "version=" + e.getVersion() + ", " +
          "classifer=" + e.getClassifier());
        Artifact pomArtifact = this.factory.createArtifactWithClassifier(
          e.getGroupId(), e.getArtifactId(), e.getVersion(),
          "jar", e.getClassifier());

        ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(pomArtifact);
        request.setRemoteRepositories(remoteRepositories);
        request.setLocalRepository(localRepository);
        ArtifactResolutionResult result = artifactResolver.resolve(request);
        for (Exception ex : result.getExceptions()) {
          getLog().error(ex);
        }
        if (result.hasExceptions()) {
          throw new MojoExecutionException("Artifact resolution failed");
        }
        getLog().info("Resolution nodes: " + result.getArtifactResolutionNodes().size());
        getLog().info("Result artifacts: " + result.getArtifacts().size());
        for (Artifact a : result.getArtifacts()) {
          getLog().info("Artifact file: " + a.getFile());
          ZipFile zip = new ZipFile(a.getFile().getPath());
          ZipEntry entry = zip.getEntry("META-INF/maven-shade-included-artifacts.list");
          if (entry != null) {
            BufferedReader reader = new BufferedReader(
              new InputStreamReader(zip.getInputStream(entry)));
            String line;
            while ((line = reader.readLine()) != null) {
              String[] items = line.split(":");
              if (items.length < 4 || items.length > 5) {
                getLog().error("Invalid full artifact ID line: " + line + ", skipping");
                continue;
              }
              String groupId = items[0];
              String artifactId = items[1];
              String type = items[2];
              String classifier = items.length == 5 ? items[3] : "";
              String version = items[items.length - 1];
              Artifact shadedJarDep = factory.createArtifactWithClassifier(
                groupId, artifactId, version, type, classifier);
              String groupArtifactType = getGroupArtifactType(shadedJarDep);
              String projectDepVersion = groupArtifactTypeToVersion.get(groupArtifactType);
              getLog().info("shaded jar dep: " + shadedJarDep.getId() + ", " +
                "version in shaded jar=" + shadedJarDep.getVersion() + ", " +
                "version in project=" + projectDepVersion +
                (shadedJarDep.getVersion().equals(projectDepVersion) ? ", EXCLUDING!" : ""));
              if (projectDepVersion != null) {

                if (shadedJarDep.getVersion().equals(projectDepVersion)) {
                  String exclude =
                    shadedJarDep.getGroupId() + ":" + shadedJarDep.getArtifactId() + ":*";
                  excludes.add(exclude);
                }
              }
            }
          }
        }
      }
      if (!excludes.isEmpty()) {
        String joinedExcludes = Joiner.on(",").join(excludes);
        getLog().debug("Excludes: " + joinedExcludes);
        project.getProperties().setProperty("maven.shade.plugin.additionalExcludes",
          joinedExcludes);
      }
    } catch (IOException ex) {
      getLog().error(ex);
      throw new MojoExecutionException("IOException", ex);
    }

  }
}
