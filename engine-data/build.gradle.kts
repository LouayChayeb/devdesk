// engine-data: the Workbench's adapters for core's data ports: Postgres, MySQL/MariaDB, SQLite (JDBC), Redis (RESP),
// MongoDB, HTTP, S3 storage, Elasticsearch, RabbitMQ and Kafka.
plugins {
    id("devdesk.java-conventions")
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
