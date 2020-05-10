package com.cbruegg.emuserver.serialization;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.UUID;

public class UUIDAdapter extends JsonAdapter<UUID> {
    @Nullable
    @Override
    public UUID fromJson(JsonReader reader) throws IOException {
        var uuidStr = reader.nextString();
        return uuidStr != null ? UUID.fromString(uuidStr) : null;
    }

    @Override
    public void toJson(JsonWriter writer, @Nullable UUID value) throws IOException {
        writer.value(value != null ? value.toString() : null);
    }
}
