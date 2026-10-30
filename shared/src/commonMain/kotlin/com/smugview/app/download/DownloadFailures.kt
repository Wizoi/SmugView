package com.smugview.app.download

class PermissionNeededException : Exception("storage permission needed")

/** The phone would not take the file (not a network or disk-full problem). */
class SaveFailedException(message: String) : Exception(message)

/** Nothing saved and no address to fetch from. */
class NoSourceException : Exception("no source for the picture")
