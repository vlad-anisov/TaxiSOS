package com.example.sostaxi

import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.util.Log

/**
 * Диалоги для реальной авторизации через Telegram OAuth
 * Использует WebView с официальным Telegram Login Widget
 */
object TelegramAuthDialogs {
    
    private const val TAG = "TelegramAuthDialogs"
    
    /**
     * Показывает диалог с реальным Telegram Login Widget
     */
    fun showTelegramLoginDialog(
        context: Context, 
        authHelper: TelegramAuthHelper,
        onAuthSuccess: (TelegramAuthHelper.TelegramAuthData) -> Unit,
        onAuthError: (String) -> Unit
    ) {
        // Проверяем, авторизован ли уже пользователь
        if (authHelper.isAuthenticated()) {
            val sharedPrefs = context.getSharedPreferences("telegram_auth_prefs", Context.MODE_PRIVATE)
            val userData = TelegramAuthHelper.TelegramAuthData(
                id = sharedPrefs.getLong("user_id", 0),
                first_name = sharedPrefs.getString("first_name", "") ?: "",
                last_name = sharedPrefs.getString("last_name", null),
                username = sharedPrefs.getString("username", null),
                phone_number = sharedPrefs.getString("phone_number", null)
            )
            onAuthSuccess(userData)
            return
        }
        
        // Показываем диалог ввода номера телефона
        showPhoneNumberDialog(context, authHelper, onAuthSuccess, onAuthError)
    }
    
    // Диалог ввода номера телефона
    private fun showPhoneNumberDialog(
        context: Context,
        authHelper: TelegramAuthHelper,
        onAuthSuccess: (TelegramAuthHelper.TelegramAuthData) -> Unit,
        onAuthError: (String) -> Unit
    ) {
        val dialogLayout = LinearLayout(context)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        
        // Заголовок
        val titleText = TextView(context)
        titleText.text = context.getString(R.string.telegram_auth_title)
        titleText.textSize = 18f
        titleText.gravity = Gravity.CENTER
        titleText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(titleText)
        
        // Описание
        val descText = TextView(context)
        descText.text = context.getString(R.string.enter_phone_description)
        descText.textSize = 14f
        descText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(descText)
        
        // Поле ввода номера телефона
        val phoneInput = EditText(context)
        phoneInput.hint = context.getString(R.string.phone_hint)
        phoneInput.inputType = InputType.TYPE_CLASS_PHONE
        phoneInput.setText("+48")
        phoneInput.setSelection(phoneInput.text.length)
        dialogLayout.addView(phoneInput)
        
        val dialog = AlertDialog.Builder(context)
            .setView(dialogLayout)
            .setPositiveButton(context.getString(R.string.send_code)) { _, _ ->
                val phoneNumber = phoneInput.text.toString().trim()
                if (phoneNumber.length >= 10) {
                    // Отправляем номер телефона
                    authHelper.setPhoneNumber(phoneNumber)
                    
                    // Показываем диалог ввода кода
                    showVerificationCodeDialog(context, authHelper, phoneNumber, onAuthSuccess, onAuthError)
                } else {
                    onAuthError(context.getString(R.string.invalid_phone))
                }
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                onAuthError(context.getString(R.string.auth_cancelled))
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }
    
    // Диалог ввода кода подтверждения
    private fun showVerificationCodeDialog(
        context: Context,
        authHelper: TelegramAuthHelper,
        phoneNumber: String,
        onAuthSuccess: (TelegramAuthHelper.TelegramAuthData) -> Unit,
        onAuthError: (String) -> Unit
    ) {
        val dialogLayout = LinearLayout(context)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        
        // Заголовок
        val titleText = TextView(context)
        titleText.text = context.getString(R.string.enter_code_title)
        titleText.textSize = 18f
        titleText.gravity = Gravity.CENTER
        titleText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(titleText)
        
        // Описание
        val descText = TextView(context)
        descText.text = context.getString(R.string.enter_code_description)
        descText.textSize = 14f
        descText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(descText)
        
        // Поле ввода кода
        val codeInput = EditText(context)
        codeInput.hint = context.getString(R.string.code_hint)
        codeInput.inputType = InputType.TYPE_CLASS_NUMBER
        dialogLayout.addView(codeInput)
        
        val dialog = AlertDialog.Builder(context)
            .setView(dialogLayout)
            .setPositiveButton(context.getString(R.string.confirm)) { _, _ ->
                val code = codeInput.text.toString().trim()
                if (code.length >= 4) {
                    // Отправляем код подтверждения
                    authHelper.checkAuthenticationCode(code)
                    
                    // Настраиваем колбэк для отслеживания состояния авторизации
                    authHelper.init(object : TelegramAuthHelper.AuthCallback {
                        override fun onAuthStateChanged(state: TelegramAuthHelper.AuthState) {
                            when (state) {
                                TelegramAuthHelper.AuthState.WAIT_PASSWORD -> {
                                    // Нужен пароль двухфакторной аутентификации
                                    showPasswordDialog(context, authHelper, onAuthSuccess, onAuthError)
                                }
                                TelegramAuthHelper.AuthState.AUTHENTICATED -> {
                                    // Успешная авторизация будет обработана в onUserDataReceived
                                }
                                TelegramAuthHelper.AuthState.ERROR -> {
                                    onAuthError("Ошибка авторизации")
                                }
                                else -> {
                                    // Другие состояния
                                }
                            }
                        }
                        
                        override fun onContactsReceived(contacts: List<TelegramAuthHelper.TelegramContact>) {
                            // Контакты получены
                        }
                        
                        override fun onUserDataReceived(userData: TelegramAuthHelper.TelegramAuthData) {
                            onAuthSuccess(userData)
                        }
                        
                        override fun onError(error: String) {
                            onAuthError(error)
                        }
                    })
                } else {
                    onAuthError(context.getString(R.string.invalid_code))
                }
            }
            .setNegativeButton(context.getString(R.string.back)) { _, _ ->
                // Возвращаемся к вводу номера телефона
                showPhoneNumberDialog(context, authHelper, onAuthSuccess, onAuthError)
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }
    
    // Диалог ввода пароля двухфакторной аутентификации
    private fun showPasswordDialog(
        context: Context,
        authHelper: TelegramAuthHelper,
        onAuthSuccess: (TelegramAuthHelper.TelegramAuthData) -> Unit,
        onAuthError: (String) -> Unit
    ) {
        val dialogLayout = LinearLayout(context)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        
        // Заголовок
        val titleText = TextView(context)
        titleText.text = context.getString(R.string.two_factor_title)
        titleText.textSize = 18f
        titleText.gravity = Gravity.CENTER
        titleText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(titleText)
        
        // Описание
        val descText = TextView(context)
        descText.text = context.getString(R.string.two_factor_description)
        descText.textSize = 14f
        descText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(descText)
        
        // Поле ввода пароля
        val passwordInput = EditText(context)
        passwordInput.hint = context.getString(R.string.password_hint)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        dialogLayout.addView(passwordInput)
        
        val dialog = AlertDialog.Builder(context)
            .setView(dialogLayout)
            .setPositiveButton(context.getString(R.string.sign_in)) { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotEmpty()) {
                    // Отправляем пароль
                    authHelper.checkAuthenticationPassword(password)
                } else {
                    onAuthError(context.getString(R.string.enter_password))
                }
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                onAuthError(context.getString(R.string.auth_cancelled))
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }
    
    /**
     * Показывает диалог подтверждения выхода
     */
    fun showLogoutConfirmDialog(context: Context, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.logout_title))
            .setMessage(context.getString(R.string.logout_message))
            .setPositiveButton(context.getString(R.string.logout)) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            .create()
            .show()
    }
    
    /**
     * Показывает диалог с информацией о пользователе
     */
    fun showUserInfoDialog(context: Context, userData: TelegramAuthHelper.TelegramAuthData) {
        val dialogLayout = LinearLayout(context)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        
        // Заголовок
        val titleText = TextView(context)
        titleText.text = context.getString(R.string.user_info_title)
        titleText.textSize = 18f
        titleText.gravity = Gravity.CENTER
        titleText.setPadding(0, 0, 0, 20)
        dialogLayout.addView(titleText)
        
        // Получаем дополнительные данные из настроек
        val sharedPrefs = context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        val firstName = sharedPrefs.getString("first_name", "") ?: ""
        val lastName = sharedPrefs.getString("last_name", "") ?: ""
        val registrationNumber = sharedPrefs.getString("registration_number", "") ?: ""
        val taxiNumber = sharedPrefs.getString("taxi_number", "") ?: ""
        val carBrand = sharedPrefs.getString("car_brand", "") ?: ""
        val carColor = sharedPrefs.getString("car_color", "") ?: ""
        
        // Имя
        if (firstName.isNotEmpty()) {
            val firstNameText = TextView(context)
            firstNameText.text = context.getString(R.string.first_name) + ": " + firstName
            firstNameText.textSize = 14f
            firstNameText.setPadding(0, 5, 0, 5)
            dialogLayout.addView(firstNameText)
        }
        
        // Фамилия
        if (lastName.isNotEmpty()) {
            val lastNameText = TextView(context)
            lastNameText.text = context.getString(R.string.last_name) + ": " + lastName
            lastNameText.textSize = 14f
            lastNameText.setPadding(0, 5, 0, 5)
            dialogLayout.addView(lastNameText)
        }
        
        // Регистрационный номер
        if (registrationNumber.isNotEmpty()) {
            val regText = TextView(context)
            regText.text = context.getString(R.string.registration_number) + ": " + registrationNumber
            regText.textSize = 14f
            regText.setPadding(0, 5, 0, 5)
            dialogLayout.addView(regText)
        }
        
        // Бортовой номер такси
        if (taxiNumber.isNotEmpty()) {
            val taxiText = TextView(context)
            taxiText.text = context.getString(R.string.taxi_board_number) + ": " + taxiNumber
            taxiText.textSize = 14f
            taxiText.setPadding(0, 5, 0, 5)
            dialogLayout.addView(taxiText)
        }
        
        // Марка автомобиля
        if (carBrand.isNotEmpty()) {
            val brandText = TextView(context)
            brandText.text = context.getString(R.string.car_brand) + ": " + carBrand
            brandText.textSize = 14f
            brandText.setPadding(0, 5, 0, 5)
            dialogLayout.addView(brandText)
        }
        
        // Цвет автомобиля
        if (carColor.isNotEmpty()) {
            val colorText = TextView(context)
            colorText.text = context.getString(R.string.car_color) + ": " + carColor
            colorText.textSize = 14f
            colorText.setPadding(0, 5, 0, 5)
            dialogLayout.addView(colorText)
        }
        
        // Номер телефона (из Telegram - неизменяемый)
        userData.phone_number?.let { phone ->
            val phoneText = TextView(context)
            // Добавляем знак "+" если его нет
            val formattedPhone = if (phone.startsWith("+")) phone else "+$phone"
            phoneText.text = context.getString(R.string.phone_from_telegram) + ": " + formattedPhone
            phoneText.textSize = 14f
            phoneText.setPadding(0, 5, 0, 5)
            phoneText.setTextColor(android.graphics.Color.GRAY) // Серый цвет для неизменяемого поля
            dialogLayout.addView(phoneText)
        }
        
        AlertDialog.Builder(context)
            .setView(dialogLayout)
            .setPositiveButton(context.getString(R.string.ok)) { _, _ -> }
            .create()
            .show()
    }
    
    /**
     * Показывает диалог ошибки авторизации
     */
    fun showAuthErrorDialog(context: Context, error: String, onRetry: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.auth_error))
            .setMessage(error)
            .setPositiveButton(context.getString(R.string.retry)) { _, _ ->
                onRetry()
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            .create()
            .show()
    }
    
    /**
     * Показывает информационный диалог о настройке бота
     */
    fun showBotSetupInfoDialog(context: Context) {
        val message = context.getString(R.string.bot_setup_message)
        
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.bot_setup_title))
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.understood), null)
            .show()
    }
    
    /**
     * Показывает диалог настройки домена
     */
    fun showDomainSetupDialog(context: Context, onDomainSet: (String) -> Unit) {
        val dialogLayout = LinearLayout(context)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        
        // Заголовок
        val titleTextView = TextView(context)
        titleTextView.text = context.getString(R.string.domain_setup_title)
        titleTextView.textSize = 18f
        titleTextView.setPadding(0, 0, 0, 20)
        dialogLayout.addView(titleTextView)
        
        // Информация
        val infoTextView = TextView(context)
        infoTextView.text = context.getString(R.string.domain_setup_info)
        infoTextView.textSize = 14f
        infoTextView.setPadding(0, 0, 0, 20)
        dialogLayout.addView(infoTextView)
        
        // Поле для ввода домена
        val domainInput = EditText(context)
        domainInput.hint = context.getString(R.string.domain_hint)
        domainInput.setText("https://sostaxi.app")
        domainInput.setPadding(20, 20, 20, 20)
        dialogLayout.addView(domainInput)
        
        // Примечание
        val noteTextView = TextView(context)
        noteTextView.text = context.getString(R.string.domain_note)
        noteTextView.textSize = 12f
        noteTextView.setPadding(0, 20, 0, 0)
        noteTextView.setTextColor(0xFF666666.toInt())
        dialogLayout.addView(noteTextView)
        
        AlertDialog.Builder(context)
            .setView(dialogLayout)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                val domain = domainInput.text.toString().trim()
                if (domain.isNotEmpty()) {
                    onDomainSet(domain)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }
} 