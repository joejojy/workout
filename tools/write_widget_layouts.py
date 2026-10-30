"""Generate the four native widget arrangements from shared, explicit XML sections."""
from pathlib import Path

res=Path(__file__).resolve().parents[1]/'app/src/main/res'
ns='xmlns:android="http://schemas.android.com/apk/res/android"'
def save(name,content):
    path=res/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content.strip()+'\n',encoding='utf-8')

save('values/widget_styles.xml','''<resources>
  <style name="WidgetText"><item name="android:layout_width">match_parent</item><item name="android:layout_height">wrap_content</item><item name="android:textColor">#FFFFFF</item><item name="android:textSize">13sp</item><item name="android:includeFontPadding">false</item></style>
  <style name="WidgetTitle" parent="WidgetText"><item name="android:textSize">22sp</item><item name="android:fontFamily">sans-serif-medium</item></style>
</resources>''')
save('layout/widget_header.xml',f'''<LinearLayout {ns} android:id="@+id/widget_header" android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:layout_marginBottom="10dp">
  <TextView android:id="@+id/widget_brand" style="@style/WidgetText" android:layout_width="0dp" android:layout_weight="1" android:text="WORKOUT" android:textSize="11sp" android:letterSpacing="0.10" android:maxLines="1" />
  <TextView android:id="@+id/week_day" style="@style/WidgetText" android:layout_width="wrap_content" android:text="W1 · MON" android:textSize="11sp" android:paddingHorizontal="7dp" android:paddingVertical="4dp" android:background="@drawable/button_green" />
</LinearLayout>''')
save('layout/widget_summary.xml',f'''<LinearLayout {ns} android:id="@+id/summary" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
  <TextView android:id="@+id/category" style="@style/WidgetText" android:textSize="11sp" android:layout_marginBottom="4dp" android:visibility="gone" />
  <TextView android:id="@+id/session_title" style="@style/WidgetTitle" android:text="Your workout" />
  <TextView android:id="@+id/target" style="@style/WidgetText" android:layout_marginTop="5dp" android:text="Running + strength" />
  <TextView android:id="@+id/target_label" style="@style/WidgetText" android:textSize="11sp" android:layout_marginTop="4dp" android:visibility="gone" />
</LinearLayout>''')
save('layout/widget_footer.xml',f'''<LinearLayout {ns} android:id="@+id/widget_footer" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:layout_marginTop="10dp">
  <TextView android:id="@+id/next_session" style="@style/WidgetText" android:textSize="11sp" android:layout_marginBottom="8dp" android:visibility="gone" />
  <TextView android:id="@+id/open_session" style="@style/WidgetText" android:layout_height="48dp" android:gravity="center" android:text="@string/view_session" android:textSize="12sp" android:background="@drawable/button_green" />
</LinearLayout>''')
save('layout/widget_row.xml',f'''<LinearLayout {ns} android:id="@+id/row_root" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:paddingTop="8dp" android:paddingBottom="8dp">
  <TextView android:id="@+id/row_title" style="@style/WidgetText" android:fontFamily="sans-serif-medium" />
  <TextView android:id="@+id/row_value" style="@style/WidgetText" android:layout_marginTop="3dp" />
  <TextView android:layout_width="match_parent" android:layout_height="1dp" android:layout_marginTop="8dp" android:background="#262C28" android:importantForAccessibility="no" />
</LinearLayout>''')
save('layout/widget_fact.xml',f'''<TextView {ns} android:id="@+id/row_title" style="@style/WidgetText" android:paddingTop="3dp" android:paddingBottom="3dp" android:textSize="12sp" />''')
body='''<LinearLayout android:id="@+id/inline_rows" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone" />
<FrameLayout android:id="@+id/list_container" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:visibility="gone" android:layout_marginTop="8dp">
  <ListView android:id="@+id/workout_list" android:layout_width="match_parent" android:layout_height="match_parent" android:divider="@android:color/transparent" android:dividerHeight="0dp" android:scrollbars="vertical" android:cacheColorHint="#000000" android:background="#000000" android:contentDescription="Workout details. Scroll for more." />
</FrameLayout>'''
week='''<LinearLayout android:id="@+id/week_strip" android:layout_width="match_parent" android:layout_height="48dp" android:layout_marginTop="8dp" android:visibility="gone">'''+''.join(f'<TextView android:id="@+id/day_{i+1}" style="@style/WidgetDay" android:text="{d}" />' for i,d in enumerate('MTWTFSS'))+'</LinearLayout>'
root=f'<FrameLayout {ns} android:id="@+id/widget_host" android:layout_width="match_parent" android:layout_height="match_parent"><LinearLayout android:id="@+id/widget_root" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:padding="12dp" android:background="@drawable/widget_background">'
agenda='<LinearLayout android:id="@+id/agenda_rows" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone" />'
for name in ['workout','tall','detailed']:
    save(f'layout/widget_{name}.xml',root+'\n<include layout="@layout/widget_header" />\n<include layout="@layout/widget_summary" />\n'+body+'\n'+agenda+(week if name=='detailed' else '')+'\n<include layout="@layout/widget_footer" />\n</LinearLayout></FrameLayout>')
wide='''<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="top">
  <LinearLayout android:layout_width="0dp" android:layout_weight="1.3" android:layout_height="wrap_content" android:orientation="vertical" android:layout_marginEnd="12dp"><include layout="@layout/widget_summary" /></LinearLayout>
  <LinearLayout android:id="@+id/wide_facts" android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical" />
</LinearLayout>'''
wide_footer='''<LinearLayout android:id="@+id/widget_footer" android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:layout_marginTop="10dp">
  <TextView android:id="@+id/next_session" style="@style/WidgetText" android:layout_width="0dp" android:layout_weight="1" android:textSize="11sp" android:layout_marginEnd="8dp" />
  <TextView android:id="@+id/open_session" style="@style/WidgetText" android:layout_width="125dp" android:layout_height="48dp" android:gravity="center" android:text="@string/view_session" android:textSize="12sp" android:background="@drawable/button_green" />
</LinearLayout>'''
save('layout/widget_wide.xml',root+'\n<include layout="@layout/widget_header" />\n'+wide+'\n'+body+'\n'+agenda+'\n'+wide_footer+'\n</LinearLayout></FrameLayout>')
