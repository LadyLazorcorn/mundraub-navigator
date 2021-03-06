package xjcl.mundraub

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Headers
import kotlinx.android.synthetic.main.activity_report_plant.*

class ReportPlant : AppCompatActivity() {

    private var intentNid : Int = -1
    private lateinit var cookie : String

    val reportData = mutableMapOf(
        "op" to "Absenden",
        "form_id" to "report_node_form"
    )

    val radioMap = mapOf(R.id.report_r0 to "0", R.id.report_r1 to "1", R.id.report_r2 to "2", R.id.report_r3 to "3", R.id.report_r4 to "4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_plant)

        intentNid = intent.getIntExtra("nid", -1)

        doWithLoginCookie(this, loginIfMissing = true, callback = { doCreate() })
    }

    private fun doCreate() {
        val sharedPref = this.getSharedPreferences("global", Context.MODE_PRIVATE)
        cookie = sharedPref.getString("cookie", null) ?: return finish()
    }

    fun reportPlant(view : View) {
        if (this.report_text_inner.text.toString().isBlank()) return

        val url = "https://mundraub.org/node/$intentNid?destination=/map?nid=$intentNid"
        Fuel.get(url).header(Headers.COOKIE to cookie).responseString { request, response, result ->

            when (response.statusCode) {
                -1 -> return@responseString runOnUiThread { Toast.makeText(this, getString(R.string.errMsgNoInternet), Toast.LENGTH_SHORT).show() }
                200 -> {}
                else -> return@responseString runOnUiThread { Toast.makeText(this, getString(R.string.errMsgAccess), Toast.LENGTH_SHORT).show() }
            }

            reportData["form_token"] = scrapeFormToken(result.get().substringAfter("edit-report-node-form-form-token"))
            reportData["report_type"] = radioMap[this.report_radio_group.checkedRadioButtonId] ?: "4"
            reportData["description"] = this.report_text_inner.text.toString()

            Log.e("reportPlant", intentNid.toString())
            Log.e("reportPlant", result.get())
            Log.e("reportPlant", reportData.toString())
            Fuel.post(url, reportData.toList()).header(Headers.COOKIE to cookie).allowRedirects(false).responseString { request, response, result2 ->

                Log.e("reportPlant", result.get())
                when (response.statusCode) {
                    -1 -> return@responseString runOnUiThread { Toast.makeText(this@ReportPlant, getString(R.string.errMsgNoInternet), Toast.LENGTH_SHORT).show() }
                    303 -> {}
                    else -> return@responseString runOnUiThread { Toast.makeText(this@ReportPlant, getString(R.string.errMsgOpFail), Toast.LENGTH_SHORT).show() }
                }

                runOnUiThread {
                    Toast.makeText(this@ReportPlant, getString(R.string.errMsgReportSuccess), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}
