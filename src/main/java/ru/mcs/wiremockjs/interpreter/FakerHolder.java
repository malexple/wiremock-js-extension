package ru.mcs.wiremockjs.interpreter;

import net.datafaker.Faker;

import java.util.Locale;

public class FakerHolder {
    private static volatile Faker instance;

    public static synchronized Faker getInstance() {
        if (instance == null) {
            instance = new Faker(new Locale("ru"));
        }
        return instance;
    }

    private FakerHolder() {}
}