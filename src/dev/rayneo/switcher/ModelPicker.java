package dev.rayneo.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

final class ModelPicker {
    static AlertDialog single(Activity activity, List<String> models, String current, Consumer<String> selected) {
        return show(activity, models, Collections.singletonList(current), false,
                values -> selected.accept(values.get(0)));
    }

    static AlertDialog multiple(Activity activity, List<String> models, List<String> current,
            Consumer<List<String>> selected) {
        return show(activity, models, current, true, selected);
    }

    private static AlertDialog show(Activity activity, List<String> models, List<String> current,
            boolean multiple, Consumer<List<String>> selected) {
        Set<String> checked = new LinkedHashSet<>();
        for (String model : models) {
            if (current.contains(model)) {
                checked.add(model);
            }
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);
        EditText filter = new EditText(activity);
        filter.setSingleLine(true);
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.setHint("搜索模型 ID");
        filter.setContentDescription("搜索模型 ID");
        content.addView(filter, new LinearLayout.LayoutParams(-1, -2));
        ListView list = new ListView(activity);
        list.setChoiceMode(multiple ? ListView.CHOICE_MODE_MULTIPLE : ListView.CHOICE_MODE_SINGLE);
        List<String> visible = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                multiple ? android.R.layout.simple_list_item_multiple_choice : android.R.layout.simple_list_item_single_choice,
                visible);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(-1,
                Math.round(320 * activity.getResources().getDisplayMetrics().density)));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(multiple ? "添加模型" : "切换模型")
                .setView(content).setNegativeButton("取消", null);
        if (multiple) {
            builder.setPositiveButton("添加所选", (dialog, which) -> selected.accept(new ArrayList<>(checked)));
        }
        AlertDialog dialog = builder.create();
        Runnable updateSelection = () -> {
            dialog.setTitle(multiple ? "添加模型（已选 " + checked.size() + "）" : "切换模型");
            if (multiple && dialog.isShowing()) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!checked.isEmpty());
            }
        };
        Runnable refresh = () -> {
            String query = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (String model : models) {
                if (model.toLowerCase(Locale.ROOT).contains(query)) {
                    visible.add(model);
                }
            }
            adapter.notifyDataSetChanged();
            list.clearChoices();
            for (int index = 0; index < visible.size(); index++) {
                list.setItemChecked(index, checked.contains(visible.get(index)));
            }
            updateSelection.run();
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            String model = visible.get(position);
            if (!multiple) {
                dialog.dismiss();
                selected.accept(Collections.singletonList(model));
                return;
            }
            if (list.isItemChecked(position)) {
                checked.add(model);
            } else {
                checked.remove(model);
            }
            updateSelection.run();
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        refresh.run();
        return dialog;
    }
}
