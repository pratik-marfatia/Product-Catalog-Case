# Starting point — adjust freely.

FROM golang:1.23-alpine AS build
WORKDIR /src
# Copy source first so go mod tidy can read imports and resolve
# the correct versions, writing go.sum inside the build stage.
# No pre-committed go.sum is required.
COPY . .
RUN go mod tidy && \
    CGO_ENABLED=0 go build -o /out/indexer .

FROM alpine:3.20
COPY --from=build /out/indexer /usr/local/bin/indexer
ENTRYPOINT ["/usr/local/bin/indexer"]
