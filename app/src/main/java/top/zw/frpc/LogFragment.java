package top.zw.frpc;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class LogFragment extends Fragment {

    private TextView tvLog;
    private ScrollView scrollView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvLog = view.findViewById(R.id.tv_log);
        scrollView = view.findViewById(R.id.log_scroll);

        Button btnClear = view.findViewById(R.id.btn_clear_log);
        btnClear.setOnClickListener(v -> {
            tvLog.setText("");
        });
    }

    public void appendLog(final String line) {
        if (tvLog == null) return;
        tvLog.post(() -> {
            tvLog.append(line + "\n");
            // Trim when too long
            if (tvLog.getText().length() > 80000) {
                String text = tvLog.getText().toString();
                tvLog.setText(text.substring(text.length() - 60000));
            }
            // Auto-scroll to bottom
            scrollView.fullScroll(View.FOCUS_DOWN);
        });
    }
}
