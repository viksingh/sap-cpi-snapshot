package com.sakiv.cpi.snapshot.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sakiv.cpi.snapshot.api.ArtifactType;
import com.sakiv.cpi.snapshot.api.CpiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "list", mixinStandardHelpOptions = true,
        description = "List integration packages and how many artifacts of each type they hold.")
public class ListCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Override
    public Integer call() throws Exception {
        CpiClient client = new CpiClient(connection.toConfig());
        List<JsonNode> packages = new ArrayList<>(client.listPackages());
        packages.sort(Comparator.comparing(p -> p.path("Id").asText()));

        System.out.printf("%-40s %-10s %6s %6s %6s %6s%n", "PACKAGE", "VERSION", "IFLOW", "VALMAP", "MSGMAP", "SCRIPT");
        for (JsonNode p : packages) {
            String id = p.path("Id").asText();
            StringBuilder counts = new StringBuilder();
            for (ArtifactType t : ArtifactType.values()) {
                String c;
                try {
                    c = String.valueOf(client.listArtifacts(id, t).size());
                } catch (Exception e) {
                    c = "err";
                }
                counts.append(String.format(" %6s", c));
            }
            System.out.printf("%-40s %-10s%s%n", id, p.path("Version").asText(""), counts);
        }
        System.out.printf("%n%d package(s)%n", packages.size());
        return 0;
    }
}
