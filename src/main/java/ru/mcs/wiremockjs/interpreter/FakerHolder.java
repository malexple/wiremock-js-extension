package ru.mcs.wiremockjs.interpreter;

import net.datafaker.Faker;

public class FakerHolder {
    private static volatile Faker instance;

    public static synchronized Faker getInstance() {
        if (instance == null) {
            instance = new Faker();
        }
        return instance;
    }

    private FakerHolder() {}
}