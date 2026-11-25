package com.budguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 遗落记录：时间、当时的距离、推定位置（含中文地址）。 */
public class HistoryActivity extends Activity {

    private final List<LeaveEvent> items = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, android.R.id.text1);
        list = findViewById(R.id.hist_list);
        empty = findViewById(R.id.hist_empty);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) showDetail(items.get(position));
            }
        });

        findViewById(R.id.hist_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        findViewById(R.id.hist_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        items.clear();
        items.addAll(new Store(this).events());
        adapter.clear();
        for (LeaveEvent e : items) {
            String head = e.timeText() + "  " + e.resultText();
            String sub = "  " + e.placeText()
                    + (e.confidence > 0 ? "  ·  可信度" + LeaveEstimator.confidenceText(e.confidence) : "");
            adapter.add(head + "\n" + sub);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showDetail(final LeaveEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("结果：").append(e.resultText()).append('\n');
        sb.append("判定时刻：").append(e.timeText()).append('\n');

        for (LeaveEvent.Part p : e.parts) {
            sb.append('\n').append(p.roleName())
                    .append(" · 最后听到 ").append(p.heardText())
                    .append(" · 判定距离 ").append(p.distanceText()).append('\n');
            if (p.loc != null) {
                sb.append("  位置 ").append(p.loc.coord()).append('\n')
                        .append("  ").append(p.loc.accText()).append('\n');
            } else {
                sb.append("  （当时没有定位记录）\n");
            }
        }

        if (e.confidence > 0) {
            sb.append("\n可信度：").append(LeaveEstimator.confidenceText(e.confidence))
                    .append("（").append(e.confidence).append("）").append('\n');
        }

        Loc est = e.estimate;
        if (est != null) {
            sb.append("\n推定落点（加权融合）：\n")
                    .append(est.coord()).append('\n').append(est.accText()).append('\n');
        }
        Loc det = e.detected;
        if (det != null) {
            sb.append("\n判定时刻位置：\n")
                    .append(det.coord()).append('\n').append(det.accText()).append('\n');
        }
        if (est == null && det == null) {
            sb.append("\n（这次没有拿到定位，可能未授予定位权限）");
        }
        if (e.address != null && !e.address.isEmpty()) {
            sb.append("\n地址：").append(e.address).append('\n');
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("遗落详情")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null);
        if (e.best() != null) {
            b.setNeutralButton("在地图中打开", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    openMap(e.best());
                }
            });
        }
        b.show();
    }

    private void openMap(Loc l) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(l.mapUrl()));
            startActivity(i);
        } catch (Exception ex) {
            toast("设备上没有可用的地图应用");
        }
    }

    private void confirmClear() {
        if (items.isEmpty()) {
            toast("没有记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清除全部遗落记录？")
                .setMessage("共 " + items.size() + " 条，清除后不可恢复。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new Store(HistoryActivity.this).clearEvents();
                        load();
                        toast("已清除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
