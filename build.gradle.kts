// Configuring JVM memory to prevent GC thrashing
// Managed via gradle.properties: org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC