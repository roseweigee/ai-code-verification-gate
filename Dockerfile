# Web console for the AI Code Verification Gate, for Google Cloud Run (or any container host).
# A full JDK is needed at runtime, not just a JRE: gate G2 compiles the AI's code in memory with javac.
# SpecBlast's own backend, built from its sources when the build passes them in:
#   docker build --build-context specblast=PATH/TO/specblast/backend/specttocode-backend .
# Without it this stage is empty and the chain page uses SpecBlast's saved output. Only the jar reaches the image.
FROM alpine:3.20 AS specblast
RUN mkdir -p /src
FROM maven:3.9-eclipse-temurin-17 AS specblast-build
COPY --from=specblast / /sb/
RUN mkdir -p /out && if [ -f /sb/pom.xml ]; then \
      cd /sb && mvn -q -B -DskipTests package && cp target/*.jar /out/specblast.jar; \
    else echo "SpecBlast sources not passed in: no SpecBlast service in this image"; fi

FROM eclipse-temurin:17-jdk

# git: the whole-project check (D-05) scans a project's Git history for secrets.
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .

# Build with the plain JDK, then prove the engine works before the image is accepted.
RUN mkdir -p build/classes \
 && javac -encoding UTF-8 -d build/classes --release 17 $(find src/main/java -name '*.java') \
 && java -cp build/classes -Daiverify.root=/app dev.aiverify.Main selftest \
 && rm -rf runs

# SpecForge, when the build checked it out next to the gate (the chain page, /chain, runs it)
RUN if [ -d specforge/src/main/java ]; then \
      rm -rf specforge/.git specforge/out specforge/build && mkdir -p specforge/build/classes \
      && javac -encoding UTF-8 -d specforge/build/classes --release 17 $(find specforge/src/main/java -name '*.java') \
      && (cd specforge && java -cp build/classes dev.specforge.Main selftest); \
    else echo "SpecForge not in the build context: the chain page will say so"; fi

COPY --from=specblast-build /out/ /app/specblast/

# Run as a non-root user; it only needs to write runs/ under /app.
RUN useradd --system --uid 10001 --home /app gate && chown -R 10001 /app
USER 10001

# Cloud Run sets PORT. With PORT set, the console listens on all interfaces and requires DEMO_PASSWORD.
ENV PORT=8080
CMD ["java", "-XX:MaxRAMPercentage=35", "-cp", "build/classes", "-Daiverify.root=/app", "dev.aiverify.Main", "console"]
