FROM eclipse-temurin:21-jdk-alpine

ARG SERVER_PORT=8888
ARG STORAGE_PATH=/app/document-storage
ARG LLM_URL=http://ollama:11434
ARG LLM_MODEL=llama3:8b-instruct-q4_K_M
ARG LLM_EMBED_MODEL=mxbai-embed-large
ARG LLM_CHUNK_SIZE=1000
ARG LLM_CHUNK_OVERLAP=200
ARG LLM_MAX_TOKEN=2048
ARG LLM_TEMPERATURE=0.3

ENV SERVER_PORT=${SERVER_PORT} \
    STORAGE_PATH=${STORAGE_PATH} \
    LLM_URL=${LLM_URL} \
    LLM_MODEL=${LLM_MODEL} \
    LLM_EMBED_MODEL=${LLM_EMBED_MODEL} \
    LLM_CHUNK_SIZE=${LLM_CHUNK_SIZE} \
    LLM_CHUNK_OVERLAP=${LLM_CHUNK_OVERLAP} \
    LLM_MAX_TOKEN=${LLM_MAX_TOKEN} \
    LLM_TEMPERATURE=${LLM_TEMPERATURE}

WORKDIR /app

RUN mkdir -p ${STORAGE_PATH} && \
    chmod 777 ${STORAGE_PATH}

COPY src/main/resources/fonts/*.TTF /tmp/fonts/

RUN apk add --no-cache fontconfig && \
    mkdir -p /usr/share/fonts/msttcorefonts && \
    cp /tmp/fonts/*.TTF /usr/share/fonts/msttcorefonts/ && \
    fc-cache -f -v && \
    rm -rf /tmp/fonts

COPY ./target/llm-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE ${SERVER_PORT}

ENTRYPOINT ["java", \
    "-Xms8g", \
    "-Xmx16g", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-XX:G1HeapRegionSize=8m", \
    "-XX:+ParallelRefProcEnabled", \
    "-XX:InitiatingHeapOccupancyPercent=45", \
    "-XX:+DisableExplicitGC", \
    "-Xlog:gc*=info:file=/app/gc.log:time,uptime,level,tags:filecount=5,filesize=100m", \
    "-jar", "app.jar", \
    "--server.port=${SERVER_PORT}", \
    "--storage.base-path=${STORAGE_PATH}" \
]
