# Public web demo of the wealth-management scenario (fund subscription suitability pre-check), for Cloud Run.
# A full JDK is needed at runtime: gate G2 compiles the AI's code in memory with javac.
# No AI key is used: the AI answers are the planted-defect scenario in replay-wealth/; every verdict is computed live.
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .

# Build, then prove the engine and the wealth scenario work before the image is accepted:
# the full scripted run must end with attempt 3 APPROVED and an intact audit chain.
RUN mkdir -p build/classes \
 && javac -d build/classes --release 17 $(find src/main/java -name '*.java') \
 && java -cp build/classes -Daiverify.root=/app dev.aiverify.Main selftest \
 && java -cp build/classes -Daiverify.root=/app dev.aiverify.Main wealth demo --full \
 && grep -q '"verdict" *: *"APPROVED"' runs-wealth/run-*-a3/result.json \
 && java -cp build/classes -Daiverify.root=/app dev.aiverify.Main wealth audit \
 && rm -rf runs runs-wealth

RUN useradd --system --uid 10001 --home /app gate && chown -R 10001 /app
USER 10001

# Cloud Run sets PORT; with PORT set the server listens on all interfaces.
ENV PORT=8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-cp", "build/classes", "-Daiverify.root=/app", "dev.aiverify.Main", "wealth-console"]
