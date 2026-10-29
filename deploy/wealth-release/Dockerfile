# Production fund-order pre-check: serves ONLY the code in release/wealth/, approved by a named person.
# A full JDK is needed: the approved source is compiled in memory at start-up, after its SHA-256 is checked.
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
COPY report/wealth-release.html ./report/wealth-release.html
COPY tests/golden-wealth ./tests/golden-wealth
COPY release/wealth ./release/wealth
RUN mkdir -p build/classes \
 && javac -d build/classes --release 17 $(find src/main/java -name '*.java') \
 && java -cp build/classes -Daiverify.root=/app dev.aiverify.Main wealth release-check
RUN useradd --system --uid 10001 --home /app svc && chown -R 10001 /app
USER 10001
ENV PORT=8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-cp", "build/classes", "-Daiverify.root=/app", "dev.aiverify.Main", "wealth-serve-release"]
