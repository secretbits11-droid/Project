// Root build file: Configuring JVM memory to prevent GC thrashing
tasks.withType<JavaExec> {
    jvmArgs("-Xmx2g", "-XX:MaxMetaspaceSize=512m", "-XX:+UseG1GC")
}

// Recommended: Also create/update 'gradle.properties' in the root directory with:
// org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC