package me.capcom.smsgateway.helpers

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

object PhoneHelper {
    fun filterPhoneNumber(phoneNumber: String, countryCode: String): String {
        val phoneUtil = PhoneNumberUtil.getInstance()
        var number = phoneUtil.parse(phoneNumber, countryCode.uppercase())
        if (!phoneUtil.isValidNumber(number)) {
            // PL-Med: numer krajowy z doklejonym "+" bez kodu kraju (np. "+600123456" z PL-Med)
            // to po parsowaniu kod kraju 60 — ponawiamy jako numer krajowy karty SIM.
            number = plmedNationalFallback(phoneUtil, phoneNumber, countryCode)
                ?: throw RuntimeException("Invalid phone number")
        }
        if (phoneUtil.getNumberType(number) != PhoneNumberUtil.PhoneNumberType.MOBILE
            && phoneUtil.getNumberType(number) != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE
        ) {
            throw RuntimeException("Invalid phone number type")
        }
        return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    private fun plmedNationalFallback(
        phoneUtil: PhoneNumberUtil,
        phoneNumber: String,
        countryCode: String,
    ) = try {
        val digits = phoneNumber.filter { it.isDigit() }
        if (!phoneNumber.trimStart().startsWith("+") || digits.length !in 6..10) {
            null
        } else {
            phoneUtil.parse(digits, countryCode.uppercase()).takeIf { phoneUtil.isValidNumber(it) }
        }
    } catch (e: NumberParseException) {
        null
    }
}
