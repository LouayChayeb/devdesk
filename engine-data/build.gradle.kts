// engine-data: the Workbench's adapters for core's data ports: Postgres, MySQL/MariaDB, SQLite (JDBC), Redis (RESP),
// MongoDB, HTTP, S3 storage, Elasticsearch, RabbitMQ and Kafka.
plugins {
    id("devdesk.java-conventions")
    id("devdesk.mutation")
    id("devdesk.fuzz")
    `java-library`
}

dependencies {
    implementation(project(":engine-core"))
    implementation(libs.postgresql)
    implementation(libs.mariadb)
    implementation(libs.sqlite)
    implementation(libs.mongodb.driver)
    implementation(libs.kafka.clients)
    implementation(libs.jackson.databind) // the API client's saved requests file
}

// Mutation testing: these tests start real databases and brokers in Docker for every run (minutes per mutant).
extra["pitestExcludedTests"] = listOf(
    "ElasticSearchTest", "S3ObjectsTest", "KafkaTopicsTest", "MysqlTest", "PostgresEditorTest", "MongoBrowserTest",
    "PostgresBrowserTest", "RabbitAdminTest", "SqsQueuesTest", "RedisBrowserTest",
).joinToString(",") { "io.github.louch.devdesk.data.$it" }
