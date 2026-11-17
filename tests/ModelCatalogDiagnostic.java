package dev.rayneo.switcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.List;

final class ModelCatalogDiagnostic {
    static JSONObject run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            ConfigStore.Config config = new ConfigStore(context).read();
            List<String> models = new ModelClient().listModels(config);
            return new JSONObject().put("model_count", models.size())
                    .put("current_model_available", models.contains(config.model));
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
