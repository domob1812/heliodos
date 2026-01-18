package eu.domob.heliodos

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Calendar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            setupCoordinateInput("manual_latitude", -90.0, 90.0, R.string.error_invalid_latitude)
            setupCoordinateInput("manual_longitude", -180.0, 180.0, R.string.error_invalid_longitude)
            setupNumberInput("manual_altitude")

            findPreference<Preference>("manual_date")?.setOnPreferenceClickListener {
                showDatePicker()
                true
            }

            findPreference<Preference>("manual_time")?.setOnPreferenceClickListener {
                showTimePicker()
                true
            }

            updateEnabledState()
            updateDateTimeSummaries()
        }

        override fun onResume() {
            super.onResume()
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            updateEnabledState()
        }

        private fun setupNumberInput(key: String) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }

        private fun setupCoordinateInput(key: String, min: Double, max: Double, errorMsgId: Int) {
            setupNumberInput(key)
            findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                try {
                    val value = (newValue as String).toDouble()
                    if (value in min..max) {
                        true
                    } else {
                        Toast.makeText(context, errorMsgId, Toast.LENGTH_SHORT).show()
                        false
                    }
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, R.string.error_invalid_number, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        private fun updateEnabledState() {
            val useLocation = findPreference<SwitchPreferenceCompat>("use_location")?.isChecked == true
            findPreference<EditTextPreference>("manual_latitude")?.isEnabled = !useLocation
            findPreference<EditTextPreference>("manual_longitude")?.isEnabled = !useLocation
            findPreference<EditTextPreference>("manual_altitude")?.isEnabled = !useLocation

            val useCurrentTime = findPreference<SwitchPreferenceCompat>("use_current_time")?.isChecked == true
            findPreference<Preference>("manual_date")?.isEnabled = !useCurrentTime
            findPreference<Preference>("manual_time")?.isEnabled = !useCurrentTime
        }

        private fun showDatePicker() {
            val calendar = getManualCalendar()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(requireContext(), { _, y, m, d ->
                val newCalendar = getManualCalendar()
                newCalendar.set(Calendar.YEAR, y)
                newCalendar.set(Calendar.MONTH, m)
                newCalendar.set(Calendar.DAY_OF_MONTH, d)
                saveManualTime(newCalendar.timeInMillis)
                updateDateTimeSummaries()
            }, year, month, day).show()
        }

        private fun showTimePicker() {
            val calendar = getManualCalendar()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            TimePickerDialog(requireContext(), { _, h, m ->
                val newCalendar = getManualCalendar()
                newCalendar.set(Calendar.HOUR_OF_DAY, h)
                newCalendar.set(Calendar.MINUTE, m)
                saveManualTime(newCalendar.timeInMillis)
                updateDateTimeSummaries()
            }, hour, minute, DateFormat.is24HourFormat(requireContext())).show()
        }

        private fun getManualCalendar(): Calendar {
            val prefs = preferenceManager.sharedPreferences
            val time = prefs?.getLong("manual_timestamp", System.currentTimeMillis()) ?: System.currentTimeMillis()
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = time
            return calendar
        }

        private fun saveManualTime(time: Long) {
            preferenceManager.sharedPreferences?.edit()?.putLong("manual_timestamp", time)?.apply()
        }

        private fun updateDateTimeSummaries() {
            val calendar = getManualCalendar()
            val dateParams = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
            val timeParams = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)

            findPreference<Preference>("manual_date")?.summary = dateParams.format(calendar.time)
            findPreference<Preference>("manual_time")?.summary = timeParams.format(calendar.time)
        }
    }
}
