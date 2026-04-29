ARTIFACT = target/stagefy.hpi
IMAGE    = stagefy-jenkins

.PHONY: build test clean docker-build docker-run

build:
	mvn clean package -DskipTests

test:
	mvn test

clean:
	mvn clean

docker-build: build
	docker build -t $(IMAGE) .

docker-run:
	docker run --rm -p 8080:8080 $(IMAGE)
