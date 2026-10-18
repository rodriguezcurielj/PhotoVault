FROM node:24-bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl unzip tzdata \
 && rm -rf /var/lib/apt/lists/*

# City names for "where was this taken" (GeoNames, CC-BY 4.0).
RUN mkdir -p /app/geo && cd /app/geo \
 && curl -fsSLO https://download.geonames.org/export/dump/cities15000.zip \
 && unzip -q cities15000.zip && rm cities15000.zip \
 && curl -fsSLO https://download.geonames.org/export/dump/admin1CodesASCII.txt \
 && curl -fsSLO https://download.geonames.org/export/dump/countryInfo.txt

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --omit=dev --no-audit --no-fund
COPY src ./src
COPY public ./public

ENV NODE_ENV=production DATA_DIR=/data PORT=8420 NODE_NO_WARNINGS=1
EXPOSE 8420
VOLUME /data
HEALTHCHECK --interval=60s --timeout=5s CMD curl -fs http://localhost:8420/api/health || exit 1
CMD ["node", "src/server.js"]
