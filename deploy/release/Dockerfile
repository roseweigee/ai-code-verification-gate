# The released ISO 8583 service: only the code in release/iso8583/, approved by a named human.
# Built by .github/workflows/release.yml after release-check passes.
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
COPY report/release.html ./report/release.html
COPY report/demo-mail ./report/demo-mail
COPY tests/production ./tests/production
COPY release ./release
RUN mkdir -p build/classes && javac -d build/classes --release 17 $(find src/main/java -name '*.java')
USER 10001
ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "exec java -cp build/classes -Daiverify.root=/app dev.aiverify.Main serve-release release/iso8583 --port ${PORT}"]
