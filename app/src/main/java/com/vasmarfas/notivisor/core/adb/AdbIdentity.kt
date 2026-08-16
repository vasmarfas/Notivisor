package com.vasmarfas.notivisor.core.adb

import android.content.Context
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import dadb.AdbKeyPair
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

object AdbIdentity {

    private const val RSA_PRIVATE = "adbkey"
    private const val RSA_PUBLIC = "adbkey.pub"
    private const val TLS_KEY = "adb_tls_private.key"
    private const val TLS_CERT = "adb_tls_cert.der"
    private const val ALGORITHM = "SHA512withRSA"

    data class Material(val privateKey: PrivateKey, val certificate: Certificate)

    @Synchronized
    fun keyPair(context: Context): AdbKeyPair {
        val privateKey = File(context.filesDir, RSA_PRIVATE)
        val publicKey = File(context.filesDir, RSA_PUBLIC)
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }

    @Synchronized
    fun tlsMaterial(context: Context): Material {
        val keyFile = File(context.filesDir, TLS_KEY)
        val certFile = File(context.filesDir, TLS_CERT)
        readTls(keyFile, certFile)?.let { return it }

        val material = generateTls()
        keyFile.writeBytes(material.privateKey.encoded)
        certFile.writeBytes(material.certificate.encoded)
        return material
    }

    private fun readTls(keyFile: File, certFile: File): Material? {
        if (!keyFile.exists() || !certFile.exists()) return null
        return runCatching {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            Material(key, cert)
        }.getOrNull()
    }

    private fun generateTls(): Material {
        val pair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        }.generateKeyPair()

        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + TEN_YEARS_MS)
        val name = X500Name("CN=Notivisor")

        val extensions = CertificateExtensions().apply {
            set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier),
            )
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }

        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(ALGORITHM)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(pair.public))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(name))
            set("extensions", extensions)
        }

        return Material(pair.private, X509CertImpl(info).apply { sign(pair.private, ALGORITHM) })
    }

    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
}
