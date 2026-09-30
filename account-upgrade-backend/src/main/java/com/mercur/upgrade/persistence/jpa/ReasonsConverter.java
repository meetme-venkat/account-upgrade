package com.mercur.upgrade.persistence.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** The failure reasons, stored as a JSON array of strings in a text column. */
@Converter
public class ReasonsConverter implements AttributeConverter<List<String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<String> reasons) {
        return JSON.writeValueAsString(reasons == null ? List.of() : reasons);
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        return json == null ? List.of() : List.of(JSON.readValue(json, String[].class));
    }
}
