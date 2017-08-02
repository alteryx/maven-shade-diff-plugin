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

import com.google.common.base.Joiner;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.plugins.shade.mojo.ArtifactSet;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads the specified shaded jar and adds all artifacts included into that jar
 * with versions different from those required by the current project to shade plugin
 * exclusions.
 * 
 * Technique used for resolving artifact:
 * http://stackoverflow.com/questions/1440224/how-can-i-download-maven-artifacts-within-a-plugin
 */
@Mojo(name = "shade-diff", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true,
  requiresDependencyResolution = ResolutionScope.RUNTIME)
public class ShadeDiffMojo extends AbstractMojo {

  private static final String SHADED_JAR_CONTENTS_ENTRY =
      "META-INF/maven-shade-included-artifacts.list";

  /**
   * Used to look up Artifacts in the remote repository.
   */
  @Component
  protected ArtifactFactory factory;

  /**
   * Used to look up Artifacts in the remote repository.
   */
  @Component
  protected RepositorySystem repositorySystem;

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

  private static String getIdWithoutVersion(Artifact a) {
    String v = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getType();
    if (StringUtils.isNotEmpty(a.getClassifier())) {
      v += ":" + a.getClassifier();
    }
    return v;
  }

  private Plugin lookupPlugin(String key) {

    List<Plugin> plugins = project.getBuildPlugins();

    for (Iterator<Plugin> iterator = plugins.iterator(); iterator.hasNext(); ) {
      Plugin plugin = iterator.next();
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
        getLog().info("maven-shade-plugin not found, skipping shade-diff execution");
        return;
      }

      if (excludeShadedJars == null) {
        getLog().info("No shaded jars specified to exclude the contents of, skipping " +
          "shade-diff execution");
        return;
      }

      Map<String, String> idToVersion = new HashMap<String, String>();

      for (Artifact artifact : project.getArtifacts()) {
        idToVersion.put(getIdWithoutVersion(artifact), artifact.getVersion());
      }

      Set<String> excludes = new TreeSet<String>();

      for (ShadedJarExclusion excludedShadedJar : excludeShadedJars) {
        ArtifactResolutionResult resolutionResult = resolveShadedJarToExclude(excludedShadedJar);
        if (resolutionResult.getArtifacts().isEmpty()) {
          throw new MojoExecutionException("Could not resolve shaded jar artifact to exclude: " +
            "groupId=" + excludedShadedJar.getGroupId() + ", " +
            "artifactId=" + excludedShadedJar.getArtifactId() + ", " +
            "version=" + excludedShadedJar.getVersion() + ", " +
            "classifier=" + excludedShadedJar.getClassifier());
        }
        for (Artifact excludedShadedJarArtifact : resolutionResult.getArtifacts()) {
          ZipFile zip = new ZipFile(excludedShadedJarArtifact.getFile().getPath());
          ZipEntry entry = zip.getEntry(SHADED_JAR_CONTENTS_ENTRY);
          if (entry != null) {
            BufferedReader reader = new BufferedReader(
              new InputStreamReader(zip.getInputStream(entry)));
            String line;
            while ((line = reader.readLine()) != null) {
              String[] items = line.split(":");
              if (items.length < 4 || items.length > 5) {
                getLog().warn("Invalid full artifact ID line from " +
                  excludedShadedJarArtifact.getId() + "'s list of " +
                  "included jars, skipping: " + line);
                continue;
              }
              String groupId = items[0];
              String artifactId = items[1];
              String type = items[2];
              String classifier = items.length == 5 ? items[3] : "";
              String version = items[items.length - 1];
              Artifact shadedJarDep = factory.createArtifactWithClassifier(
                groupId, artifactId, version, type, classifier);
              String groupArtifactType = getIdWithoutVersion(shadedJarDep);
              String projectDepVersion = idToVersion.get(groupArtifactType);
              if (projectDepVersion != null &&
                  shadedJarDep.getVersion().equals(projectDepVersion)) {
                String exclude =
                  shadedJarDep.getGroupId() + ":" + shadedJarDep.getArtifactId() + ":*";
                if (!excludes.contains(exclude)) {
                  excludes.add(exclude);
                  getLog().info("Excluding from shaded jar: " + exclude + " (already included in " +
                    excludedShadedJarArtifact.getId() + ")");
                }
              }
            }
          } else {
            // We make this a build failure, because this indicates that the shaded jar was not
            // built correctly.
            throw new MojoExecutionException(
              "No contents entry " + SHADED_JAR_CONTENTS_ENTRY + " found in " +
                excludedShadedJarArtifact.getFile().getPath());
          }
        }
      }
      if (!excludes.isEmpty()) {
        String joinedExcludes = Joiner.on(",").join(excludes);
        project.getProperties().setProperty("maven.shade.plugin.additionalExcludes",
          joinedExcludes);
      }
    } catch (IOException ex) {
      getLog().error(ex);
      throw new MojoExecutionException("IOException", ex);
    }

  }

  private ArtifactResolutionResult resolveShadedJarToExclude(ShadedJarExclusion excludedShadedJar)
      throws MojoExecutionException {
    Artifact excludedShadedJarArtifact = this.factory.createArtifactWithClassifier(
      excludedShadedJar.getGroupId(), excludedShadedJar.getArtifactId(),
      excludedShadedJar.getVersion(), "jar",
      excludedShadedJar.getClassifier() == null ? "" : excludedShadedJar.getClassifier());

    ArtifactResolutionRequest request = new ArtifactResolutionRequest();
    request.setArtifact(excludedShadedJarArtifact);
    request.setRemoteRepositories(remoteRepositories);
    request.setLocalRepository(localRepository);
    ArtifactResolutionResult result = repositorySystem.resolve(request);
    for (Exception ex : result.getExceptions()) {
      getLog().error(ex);
    }
    if (result.hasExceptions()) {
      throw new MojoExecutionException("Artifact resolution failed");
    }
    return result;
  }
}
