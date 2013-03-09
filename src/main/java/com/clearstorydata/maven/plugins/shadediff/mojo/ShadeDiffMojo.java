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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
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
  private List<ShadedJarExclusion> excludeShadedJars;

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

    Plugin shadePlugin = lookupPlugin("org.apache.maven.plugins:maven-shade-plugin");
    if (shadePlugin == null) {
      throw new MojoExecutionException("maven-shade-plugin not found");
    }

    Map<String, String> groupArtifactTypeToVersion = new HashMap<String, String>();

    for (Artifact artifact : project.getArtifacts()) {
      getLog().info("dependency: " + artifact.getId());
      groupArtifactTypeToVersion.put(getGroupArtifactType(artifact), artifact.getVersion());
    }

    getLog().info("excluded shaded jars: " + excludeShadedJars.size());

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
        ZipFile zip;
        try {
          zip = new ZipFile(a.getFile().getPath());
        } catch (IOException ex) {
          throw new MojoExecutionException("Could not open zip file " + a.getFile().getPath(),
            ex);
        }
        // ZipEntry entry = zip.getEntry("META-INF/maven-shade-included-artifacts.list");
        // if (entry != null) {
        for (Enumeration list = zip.entries(); list.hasMoreElements(); ) {
          ZipEntry entry = (ZipEntry) list.nextElement();
          String entryName = entry.getName();
          if (entryName.startsWith("META-INF/") && entryName.endsWith("/pom.properties")) {
            Properties properties = new Properties();
            try {
              InputStream inputStream = zip.getInputStream(entry);
              properties.load(inputStream);
              inputStream.close();
            } catch (IOException ex) {
              throw new MojoExecutionException("Could not read entry: " + entryName);
            }
            if (false) {
              getLog().info("Shaded jar dependency: " +
                "groupId=" + properties.getProperty("groupId") + ", " +
                "artifactId=" + properties.getProperty("artifactId") + ", " +
                "version=" + properties.getProperty("version") + ", " +
                "classifier=" + properties.getProperty("classifer") + ", " +
                "type=" + properties.getProperty("type"));
            }
            String artifactType = properties.getProperty("type");
            Artifact shadedJarDep = factory.createArtifactWithClassifier(
              properties.getProperty("groupId"),
              properties.getProperty("artifactId"),
              properties.getProperty("version"),
              artifactType == null ? "jar" : artifactType,
              properties.getProperty("classifier"));
            String groupArtifactType = getGroupArtifactType(shadedJarDep);
            String projectDepVersion = groupArtifactTypeToVersion.get(groupArtifactType);
            getLog().info("shaded jar dep: " + shadedJarDep.getId() + ", " +
              "version in shaded jar=" + shadedJarDep.getVersion() + ", " +
              "version in project=" + projectDepVersion +
              (shadedJarDep.getVersion().equals(projectDepVersion) ? ", EXCLUDING!" : ""));
            if (projectDepVersion != null) {

              if (shadedJarDep.getVersion().equals(projectDepVersion)) {
                // Add exclusion
//                Xpp3Dom config = (Xpp3Dom) shadePlugin.getConfiguration();
//                Xpp3Dom exclude = new Xpp3Dom("exclude");
//                getLog().info(config.toString());
//                exclude.setValue(
                String exclude =
                  shadedJarDep.getGroupId() + ":" + shadedJarDep.getArtifactId() + ":*";

                excludes.add(exclude);
//                config.getChild("artifactSet").getChild("excludes").addChild(exclude);
              }
            }

          }
        }
      }


    }

    if (!excludes.isEmpty()) {
      String joinedExcludes = Joiner.on(",").join(excludes);
      getLog().debug("Excludes: " + joinedExcludes);
      project.getProperties().setProperty("maven.shade.plugin.additionalExcludes", joinedExcludes);
    }

    File f = outputDirectory;

    if (!f.exists()) {
      f.mkdirs();
    }

    File touch = new File(f, "touch.txt");

    FileWriter w = null;
    try {
      w = new FileWriter(touch);

      w.write("touch.txt");
    } catch (IOException e) {
      throw new MojoExecutionException("Error creating file " + touch, e);
    } finally {
      if (w != null) {
        try {
          w.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }
  }
}
