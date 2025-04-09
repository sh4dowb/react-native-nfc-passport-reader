package com.nfcpassportreader

import android.content.Context
import android.nfc.tech.IsoDep
import com.nfcpassportreader.utils.*
import com.nfcpassportreader.dto.*
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardSecurityFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG5File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.iso19794.FaceImageInfo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream

class NfcPassportReader(context: Context) {
  private val bitmapUtil = BitmapUtil(context)
  private val dateUtil = DateUtil()
  private val availableFilesMap = mapOf(
    "EF_CARD_ACCESS" to CardSecurityFile.EF_CARD_ACCESS, "EF_COM" to CardSecurityFile.EF_COM, "EF_SOD" to CardSecurityFile.EF_SOD,
    "EF_DG1" to DG1File.EF_DG1, "EF_DG2" to DG2File.EF_DG2, "EF_DG3" to DG2File.EF_DG3, "EF_DG4" to DG2File.EF_DG4,
    "EF_DG5" to DG5File.EF_DG5, "EF_DG6" to DG2File.EF_DG6, "EF_DG7" to DG2File.EF_DG7, "EF_DG8" to DG2File.EF_DG8,
    "EF_DG9" to DG2File.EF_DG9, "EF_DG10" to DG2File.EF_DG10, "EF_DG11" to DG11File.EF_DG11, "EF_DG12" to DG12File.EF_DG12,
    "EF_DG13" to DG2File.EF_DG13, "EF_DG14" to DG2File.EF_DG14, "EF_DG15" to DG2File.EF_DG15, "EF_DG16" to DG2File.EF_DG16
  )

  fun readPassport(isoDep: IsoDep, bacKey: BACKeySpec, includeImages: Boolean, extraFiles: List<String> = emptyList()): NfcResult {
    isoDep.timeout = 10000

    val cardService = CardService.getInstance(isoDep)
    cardService.open()

    val service = PassportService(
      cardService,
      PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
      PassportService.DEFAULT_MAX_BLOCKSIZE,
      false,
      false
    )

    service.open()
    service.sendSelectApplet(false)
    service.doBAC(bacKey)

    val nfcResult = NfcResult()
    nfcResult.rawFiles = HashMap()
    val readFiles = mutableListOf("EF_COM", "EF_DG1", "EF_DG11", "EF_SOD")
    if (includeImages) {
      readFiles += listOf("EF_DG2", "EF_DG5")
    }
    readFiles += extraFiles

    for (file in readFiles) {
      if (availableFilesMap[file] == null || nfcResult.rawFiles[file] != null) {
        continue
      }
      try {
        nfcResult.rawFiles[file] = service.getInputStream(availableFilesMap[file]!!).readBytes()
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }

    val mrzInfo = DG1File(ByteArrayInputStream(nfcResult.rawFiles["EF_DG1"])).mrzInfo
    nfcResult.identityNo = mrzInfo.personalNumber
    nfcResult.gender = mrzInfo.gender.toString()
    nfcResult.expiryDate = dateUtil.convertFromMrzDate(mrzInfo.dateOfExpiry)
    nfcResult.documentNo = mrzInfo.documentNumber
    nfcResult.nationality = mrzInfo.nationality
    nfcResult.mrz = mrzInfo.toString()
    nfcResult.firstName = mrzInfo.secondaryIdentifier
    nfcResult.lastName = mrzInfo.primaryIdentifier
    nfcResult.birthDate = dateUtil.convertFromMrzDate(mrzInfo.dateOfBirth)

    if (nfcResult.rawFiles["EF_DG11"] != null) {
      val dg11File = DG11File(ByteArrayInputStream(nfcResult.rawFiles["EF_DG11"]))
      val name = dg11File.nameOfHolder.substringAfterLast("<<").replace("<", " ")
      val surname = dg11File.nameOfHolder.substringBeforeLast("<<")

      nfcResult.firstName = name
      nfcResult.lastName = surname
      nfcResult.placeOfBirth = dg11File.placeOfBirth.joinToString(separator = " ")
      nfcResult.birthDate = dateUtil.convertFromNfcDate(dg11File.fullDateOfBirth)
    }

    if (includeImages && nfcResult.rawFiles["EF_DG2"] != null) {
      try {
        val dg2File = DG2File(ByteArrayInputStream(nfcResult.rawFiles["EF_DG2"]))
        val faceInfos = dg2File.faceInfos
        val allFaceImageInfos: MutableList<FaceImageInfo> = ArrayList()
        for (faceInfo in faceInfos) {
          allFaceImageInfos.addAll(faceInfo.faceImageInfos)
        }
        if (allFaceImageInfos.isNotEmpty()) {
          val faceImageInfo = allFaceImageInfos.iterator().next()
          val image = bitmapUtil.getImage(faceImageInfo)
          nfcResult.originalFacePhoto = image
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }

    return nfcResult
  }
}

private fun InputStream.readBytes(): ByteArray {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    var n: Int
    while (this.read(buffer).also { n = it } != -1) {
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
