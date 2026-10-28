package com.appupdatehider;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置界面：列出本机全部应用，勾选需要在应用商店中“隐藏更新信息”的应用。
 *
 *   - 顶部搜索框：按应用名或包名实时过滤
 *   - 已勾选的应用自动置顶，方便查看与取消
 *   - 搜索框有内容时，按返回键先清空搜索回到主列表，再按一次才退出
 *
 * 黑名单写入 SharedPreferences（blocked.xml，key = blocked_packages），
 * 模块在应用商店进程里通过 XSharedPreferences 读取。
 */
public class SettingsActivity extends Activity {

    // 与模块读取的 SharedPreferences 名称 / key 必须一致
    static final String PREF_NAME = "blocked";
    static final String KEY_BLOCKED = "blocked_packages";

    private PackageManager pm;
    private List<ApplicationInfo> allApps = new ArrayList<>();
    private Set<String> selected = new HashSet<>();   // 已勾选（隐藏）的包名

    private EditText searchBox;
    private TextView tvCount;
    private ListView listView;
    private AppAdapter adapter;

    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        pm = getPackageManager();

        // 读取已保存的黑名单（getStringSet 返回只读集合，需复制到可写 Set）
        Set<String> saved = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getStringSet(KEY_BLOCKED, Collections.<String>emptySet());
        selected = new HashSet<>(saved);

        // 载入并排序全部已安装应用
        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        Collections.sort(allApps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return loadLabel(a).compareToIgnoreCase(loadLabel(b));
            }
        });

        searchBox = findViewById(R.id.search_box);
        tvCount = findViewById(R.id.tv_count);
        listView = findViewById(R.id.app_list);

        // 搜索：实时过滤
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                currentQuery = s.toString().trim();
                refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 列表点击：切换勾选状态
        listView.setOnItemClickListener(new ListView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String pkg = adapter.getItem(position).info.packageName;
                if (selected.contains(pkg)) selected.remove(pkg);
                else selected.add(pkg);
                saveSelected();
                refresh();          // 重新构建列表：勾选的应用立即置顶
                updateCount();
            }
        });

        adapter = new AppAdapter(this);
        listView.setAdapter(adapter);
        updateCount();
        refresh();
    }

    /**
     * 返回键：搜索框有内容时，先清空搜索、回到主列表（不退出 App）；
     * 搜索框为空时，正常退出。
     */
    @Override
    public void onBackPressed() {
        if (searchBox != null && searchBox.getText().length() > 0) {
            searchBox.setText("");
            searchBox.setSelection(0);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 按当前搜索词构建单一列表：
     * 已勾选的应用排在最前面，其余紧随其后，整张列表始终完整可见。
     */
    private void refresh() {
        List<ApplicationInfo> selectedApps = new ArrayList<>();
        List<ApplicationInfo> others = new ArrayList<>();

        String q = currentQuery.toLowerCase();
        for (ApplicationInfo info : allApps) {
            String label = loadLabel(info);
            String pkg = info.packageName;
            if (!q.isEmpty()
                    && !label.toLowerCase().contains(q)
                    && !pkg.toLowerCase().contains(q)) {
                continue;
            }
            if (selected.contains(pkg)) selectedApps.add(info);
            else others.add(info);
        }

        // 组内按应用名排序，保证列表稳定
        Comparator<ApplicationInfo> byLabel = new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return loadLabel(a).compareToIgnoreCase(loadLabel(b));
            }
        };
        Collections.sort(selectedApps, byLabel);
        Collections.sort(others, byLabel);

        List<ApplicationInfo> result = new ArrayList<>();
        result.addAll(selectedApps);
        result.addAll(others);

        adapter.setItems(result);
        adapter.notifyDataSetChanged();
    }

    private void updateCount() {
        tvCount.setText("已隐藏 " + selected.size() + " 个应用");
    }

    private void saveSelected() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED, new HashSet<>(selected))
                .commit();
        // 兜底：把 prefs 文件设为可读（主路径是 xposedsharedprefs，由 LSPosed 提供）
        makePrefReadable();
        Toast.makeText(this, "已保存，重启应用商店后生效", Toast.LENGTH_SHORT).show();
    }

    /** 让黑名单文件对其它进程可读（目录也要可进入，否则跨 UID 读不到） */
    private void makePrefReadable() {
        try {
            java.io.File dataDir = new java.io.File(getApplicationInfo().dataDir);
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            java.io.File f = new java.io.File(prefsDir, PREF_NAME + ".xml");
            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            if (f.exists()) {
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private String loadLabel(ApplicationInfo info) {
        CharSequence cs = info.loadLabel(pm);
        return cs != null ? cs.toString() : info.packageName;
    }

    // ============ 列表数据模型 ============
    static class AppItem {
        final ApplicationInfo info;

        AppItem(ApplicationInfo info) {
            this.info = info;
        }
    }

    // ============ 自定义适配器 ============
    class AppAdapter extends BaseAdapter {
        private final Context ctx;
        private List<AppItem> items = new ArrayList<>();

        AppAdapter(Context ctx) { this.ctx = ctx; }

        void setItems(List<ApplicationInfo> apps) {
            items = new ArrayList<>(apps.size());
            for (ApplicationInfo a : apps) items.add(new AppItem(a));
        }

        @Override public int getCount() { return items.size(); }
        @Override public AppItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(ctx).inflate(R.layout.app_row, parent, false);
                h = new ViewHolder();
                h.icon = convertView.findViewById(R.id.app_icon);
                h.name = convertView.findViewById(R.id.app_name);
                h.pkg = convertView.findViewById(R.id.app_pkg);
                h.check = convertView.findViewById(R.id.app_check);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            ApplicationInfo info = items.get(position).info;
            h.icon.setImageDrawable(info.loadIcon(pm));
            String label = loadLabel(info);
            boolean on = selected.contains(info.packageName);
            h.name.setText(on ? "✓ " + label : label);
            h.pkg.setText(info.packageName);
            h.check.setChecked(on);
            return convertView;
        }

        class ViewHolder {
            ImageView icon;
            TextView name;
            TextView pkg;
            CheckBox check;
        }
    }
}
