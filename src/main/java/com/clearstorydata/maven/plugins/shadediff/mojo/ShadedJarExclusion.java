package com.clearstorydata.maven.plugins.shadediff.mojo;

public class ShadedJarExclusion {
  private String groupId;
  private String artifactId;
  private String version;
  private String classifier;

  public String getGroupId() {
    return groupId;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public String getVersion() {
    return version;
  }

  public String getClassifier() {
    return classifier;
  }
}
