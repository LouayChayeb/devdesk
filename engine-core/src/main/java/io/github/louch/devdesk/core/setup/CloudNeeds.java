package io.github.louch.devdesk.core.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * "This code talks to AWS": the cloud services an app's dependencies name (S3, SQS, SNS, DynamoDB), so the wizard can
 * offer a local stand-in for them, like Mailpit for e-mail. Read from the dependency files only, never from code.
 */
public final class CloudNeeds {

    /** Files that list a project's libraries, per language. */
    static final List<String> FILES = List.of(
            "package.json",
            "requirements.txt",
            "pyproject.toml",
            "Pipfile",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "go.mod",
            "Gemfile",
            "composer.json");

    /** Text that names one AWS service's library, in the languages above. */
    private static final Map<String, List<String>> SIGNS = Map.of(
            "s3",
                    List.of(
                            "@aws-sdk/client-s3",
                            "awssdk:s3",
                            "<artifactId>s3</artifactId>",
                            "service/s3",
                            "aws-sdk-s3",
                            "spring-cloud-aws-starter-s3"),
            "sqs",
                    List.of(
                            "@aws-sdk/client-sqs",
                            "awssdk:sqs",
                            "<artifactId>sqs</artifactId>",
                            "service/sqs",
                            "aws-sdk-sqs",
                            "spring-cloud-aws-starter-sqs"),
            "sns",
                    List.of(
                            "@aws-sdk/client-sns",
                            "awssdk:sns",
                            "<artifactId>sns</artifactId>",
                            "service/sns",
                            "aws-sdk-sns"),
            "dynamodb",
                    List.of(
                            "@aws-sdk/client-dynamodb",
                            "@aws-sdk/lib-dynamodb",
                            "awssdk:dynamodb",
                            "<artifactId>dynamodb</artifactId>",
                            "service/dynamodb",
                            "aws-sdk-dynamodb"));

    /** Libraries that cover every AWS service: which ones the app uses can't be told from them. */
    private static final List<String> ALL_OF_AWS =
            List.of("\"aws-sdk\"", "boto3", "aws-sdk-go\"", "aws/aws-sdk-php", "\"aws-sdk\" ", "gem 'aws-sdk'");

    private CloudNeeds() {}

    /**
     * @param services what it uses: s3, sqs, sns, dynamodb, or "aws" when only an all-in-one SDK was found
     * @param foundIn the files that said so, for the wizard to show why
     * @param suggestion the stand-in to add: "s3", "sqs" or "localstack" (several services, or SNS/DynamoDB)
     */
    public record Result(Set<String> services, List<String> foundIn, Optional<String> suggestion) {}

    /**
     * @param folders the project folder and its app folders, relative ("", "api", "worker")
     * @param readFile reads a file relative to the project folder; empty if it isn't there
     */
    public static Result scan(List<String> folders, Function<String, Optional<String>> readFile) {
        Set<String> services = new LinkedHashSet<>();
        Map<String, Boolean> files = new LinkedHashMap<>();
        for (String folder : folders) {
            for (String name : FILES) {
                String path = folder.isEmpty() ? name : folder + "/" + name;
                Optional<String> text = readFile.apply(path);
                if (text.isEmpty()) {
                    continue;
                }
                SIGNS.forEach((service, signs) -> {
                    if (signs.stream().anyMatch(text.get()::contains)) {
                        services.add(service);
                        files.put(path, true);
                    }
                });
                if (ALL_OF_AWS.stream().anyMatch(text.get()::contains)) {
                    services.add("aws");
                    files.put(path, true);
                }
            }
        }
        Set<String> named = new LinkedHashSet<>(services);
        named.remove("aws");
        Optional<String> suggestion;
        if (services.isEmpty()) {
            suggestion = Optional.empty();
        } else if (named.equals(Set.of("s3"))) {
            suggestion = Optional.of("s3");
        } else if (named.equals(Set.of("sqs"))) {
            suggestion = Optional.of("sqs");
        } else {
            suggestion = Optional.of("localstack"); // several, SNS/DynamoDB, or "some of AWS"
        }
        return new Result(services, new ArrayList<>(files.keySet()), suggestion);
    }
}
