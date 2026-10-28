# Web console for the AI Code Verification Gate, for Google Cloud Run (or any container host).
# A full JDK is needed at runtime, not just a JRE: gate G2 compiles the AI's code in memory with javac.
FROM eclipse-temurin:17-jdk

# git: the whole-project check (D-05) scans a project's Git history for secrets.
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .

# Build with the plain JDK, then prove the engine works before the image is accepted.
RUN mkdir -p build/classes \
 && javac -d build/classes --release 17 $(find src/main/java -name '*.java') \
 && java -cp build/classes -Daiverify.root=/app dev.aiverify.Main selftest \
 && rm -rf runs

# Run as a non-root user; it only needs to write runs/ under /app.
RUN useradd --system --uid 10001 --home /app gate && chown -R 10001 /app
USER 10001

# Cloud Run sets PORT. With PORT set, the console listens on all interfaces and requires DEMO_PASSWORD.
ENV PORT=8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-cp", "build/classes", "-Daiverify.root=/app", "dev.aiverify.Main", "console"]
