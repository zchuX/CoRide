package com.coride.lambda.util

object Validation {
  def nonEmpty(value: String): Boolean = value != null && value.trim.nonEmpty

  def isValidEmail(email: String): Boolean = {
    if (!nonEmpty(email)) false
    else email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
  }

  def isValidPassword(password: String): Boolean = {
    if (!nonEmpty(password)) false
    else password.length >= 6
  }

  def isValidPhoneE164(phone: String): Boolean = {
    if (!nonEmpty(phone)) false
    else phone.matches("^\\+[1-9]\\d{1,14}$")
  }

  // Username: letters, numbers, hyphen, underscore only
  def isValidUsername(username: String): Boolean = {
    if (!nonEmpty(username)) false
    else username.matches("^[A-Za-z0-9_-]+$")
  }
}