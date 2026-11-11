package me.capcom.smsgateway.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.FragmentPlmedPairingBinding
import me.capcom.smsgateway.modules.localserver.WebService
import me.capcom.smsgateway.modules.plmed.PlmedPairingService
import me.capcom.smsgateway.modules.plmed.PlmedStatusService
import org.koin.android.ext.android.inject
import java.util.UUID

/**
 * PL-Med: ekran "Połącz z PL-Med" — skan kodu QR z okna PL-Med albo wpisanie 6-znakowego kodu.
 */
class PlmedPairingFragment : Fragment() {
    private var _binding: FragmentPlmedPairingBinding? = null
    private val binding get() = _binding!!

    private val pairingSvc: PlmedPairingService by inject()

    private var job: Job? = null
    private var pendingInput: String? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        if (pairingSvc.parseInput(contents) == null) {
            showResult(false, getString(R.string.plmed_pair_err_bad_qr))
            return@registerForActivityResult
        }
        startPairing(contents)
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingInput?.let { runPairing(it) }
            pendingInput = null
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlmedPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // tlo jak w BasePreferenceFragment — ekran nakłada się na listę ustawień
        val backgroundValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorBackground, backgroundValue, true)
        view.setBackgroundColor(backgroundValue.data)

        binding.editCode.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))

        binding.buttonScan.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.plmed_pair_scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            scanLauncher.launch(options)
        }
        binding.buttonConnect.setOnClickListener {
            val text = binding.editCode.text?.toString() ?: ""
            if (pairingSvc.parseInput(text) == null) {
                binding.layoutCode.error = getString(R.string.plmed_pair_err_bad_code)
                return@setOnClickListener
            }
            binding.layoutCode.error = null
            startPairing(text)
        }

        showLastPairing()
    }

    private fun startPairing(input: String) {
        if (job?.isActive == true) return
        // bramka startuje z uprawnieniami jak po przycisku Start; bez nich i tak sparujemy,
        // a ekran glowny pokaze "brak uprawnienia SMS"
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && WebService.STATUS.value != true) {
            pendingInput = input
            permissionsLauncher.launch(missing.toTypedArray())
            return
        }
        runPairing(input)
    }

    private fun runPairing(input: String) {
        setBusy(true)
        binding.textResult.isVisible = false
        job = viewLifecycleOwner.lifecycleScope.launch {
            var paired = false
            try {
                val outcome = pairingSvc.pair(requireContext(), input) { step ->
                    _binding?.textStep?.text = step
                }
                val text = if (outcome.ok) {
                    getString(R.string.plmed_pair_ok, outcome.clinic.ifEmpty { "PL-Med" }) +
                            (if (outcome.message.isNotEmpty()) "\n" + outcome.message else "")
                } else {
                    outcome.message.ifEmpty { getString(R.string.plmed_pair_no_confirm) }
                }
                showResult(outcome.ok, text)
                paired = outcome.ok
            } catch (e: PlmedPairingService.PairingException) {
                showResult(false, e.message ?: getString(R.string.plmed_pair_err_network))
            } catch (e: Throwable) {
                showResult(false, getString(R.string.plmed_pair_err_bad_response) + "\n" + (e.message ?: ""))
            } finally {
                setBusy(false)
                showLastPairing()
            }
            // PL-Med W9-1: po sukcesie od razu na Start (potwierdzenie: zielone "Polaczono z PL-Med", W9-2);
            // po kazdej porazce zostajemy - recepcja ma przeczytac przyczyne
            if (paired) returnToStart()
        }
    }

    // PL-Med W9-1: zamkniecie ekranu parowania i zakladka Start - obie drogi wejscia (odsylacz na Start i
    // Ustawienia -> "Polacz z PL-Med...") otwieraja ten ekran przez open() z nazwanym wpisem na stosie.
    // Tylko gdy ekran jest nadal na wierzchu: uzytkownik mogl w tym czasie wyjsc (Wstecz, inna zakladka, inna
    // aplikacja); po onSaveInstanceState (obrot, tlo) transakcji nie wolno - wtedy ekran zostaje z wynikiem.
    private fun returnToStart() {
        if (BuildConfig.CLOUD_ENABLED) return
        if (!isAdded || isRemoving || !isResumed) return
        val fm = parentFragmentManager
        if (fm.isStateSaved) return
        val v = view ?: return
        if (!v.isShown || !v.getGlobalVisibleRect(Rect())) return
        val entry = arguments?.getString(ARG_BACK_STACK_ENTRY) ?: return
        fm.popBackStack(entry, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        (activity as? MainActivity)?.selectTab(MainActivity.TAB_INDEX_HOME)
    }

    private fun setBusy(busy: Boolean) {
        val b = _binding ?: return
        b.progress.isVisible = busy
        b.textStep.isVisible = busy
        if (!busy) b.textStep.text = ""
        b.buttonScan.isEnabled = !busy
        b.buttonConnect.isEnabled = !busy
        b.editCode.isEnabled = !busy
    }

    private fun showResult(ok: Boolean, text: String) {
        val b = _binding ?: return
        b.textResult.isVisible = true
        b.textResult.text = (if (ok) "✓ " else "⚠ ") + text
        b.textResult.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (ok) R.color.teal_700 else R.color.red_800
            )
        )
    }

    private fun showLastPairing() {
        val b = _binding ?: return
        val last = pairingSvc.lastPairing
        b.textLastPairing.isVisible = last != null
        if (last != null) {
            b.textLastPairing.text = getString(
                if (last.ok) R.string.plmed_pair_last_ok else R.string.plmed_pair_last_failed,
                last.clinic.ifEmpty { "PL-Med" },
                PlmedStatusService.formatMoment(requireContext(), last.at)
            )
        }
    }

    private fun requiredPermissions(): List<String> = listOfNotNull(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.READ_PHONE_NUMBERS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "plmed_pairing"
        private const val ARG_BACK_STACK_ENTRY = "back_stack_entry"

        /**
         * PL-Med: otwiera ekran parowania jako nakladke na stronie [host] (Start - odsylacz, D-W7-5; Ustawienia -
         * "Polacz z PL-Med...") tak jak podekrany ustawien (PreferenceFragmentCompat: replace w kontenerze strony
         * + stos powrotu; Wstecz wraca). W9-1: wpis na stosie ma wlasna nazwe - po udanym parowaniu ekran zdejmuje
         * dokladnie siebie ([returnToStart]).
         */
        fun open(host: Fragment) {
            val fm = host.parentFragmentManager
            if (fm.isStateSaved) return
            val containerId = (host.view?.parent as? View)?.id ?: return
            if (containerId == View.NO_ID) return
            // ekran juz otwarty na tej stronie (np. drugi stuk)
            if (fm.fragments.any { it is PlmedPairingFragment && it.id == containerId && !it.isRemoving }) return
            val entry = "$TAG:${UUID.randomUUID()}"
            val fragment = PlmedPairingFragment().apply {
                arguments = bundleOf(ARG_BACK_STACK_ENTRY to entry)
            }
            fm.beginTransaction()
                .replace(containerId, fragment, TAG)
                .addToBackStack(entry)
                .commit()
        }
    }
}
