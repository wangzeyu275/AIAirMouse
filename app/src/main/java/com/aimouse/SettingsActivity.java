package com.aimouse;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

/**
 * 设置页 — 灵敏度、波形图显示与手势控制开关。
 * 所有设置直接写入 SharedPreferences，主界面在 onResume 时重新读取并生效。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton btnBack = findViewById(R.id.btnBack);
        TextView tvSensitivityValue = findViewById(R.id.tvSensitivityValue);
        SeekBar seekBarSensitivity = findViewById(R.id.seekBarSensitivity);
        SwitchMaterial switchWaveform = findViewById(R.id.switchWaveform);
        SwitchMaterial switchGesture = findViewById(R.id.switchGesture);

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);

        // 先初始化控件状态，再挂监听，避免加载时回写
        int sensitivity = prefs.getInt(MainActivity.KEY_SENSITIVITY, 100);
        seekBarSensitivity.setProgress(sensitivity);
        tvSensitivityValue.setText(String.format(Locale.US, "%.1fx", sensitivity / 100.0));
        switchWaveform.setChecked(prefs.getBoolean(MainActivity.KEY_SHOW_WAVEFORM, false));
        switchGesture.setChecked(prefs.getBoolean(MainActivity.KEY_GESTURE_ENABLED, true));

        btnBack.setOnClickListener(v -> finish());

        seekBarSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                tvSensitivityValue.setText(String.format(Locale.US, "%.1fx", progress / 100.0));
                prefs.edit().putInt(MainActivity.KEY_SENSITIVITY, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        switchWaveform.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(MainActivity.KEY_SHOW_WAVEFORM, isChecked).apply());

        switchGesture.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(MainActivity.KEY_GESTURE_ENABLED, isChecked).apply());
    }
}
