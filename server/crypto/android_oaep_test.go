package crypto

import (
	"bytes"
	stdcrypto "crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestAndroidOAEPCompatibility(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	for _, message := range [][]byte{nil, []byte("32-byte-key-for-android-keystore!"), make([]byte, 190)} {
		encrypted, err := encryptAndroidOAEP(&key.PublicKey, message)
		if err != nil {
			t.Fatal(err)
		}
		plain, err := key.Decrypt(rand.Reader, encrypted, &rsa.OAEPOptions{Hash: stdcrypto.SHA256, MGFHash: stdcrypto.SHA1})
		if err != nil || !bytes.Equal(plain, message) {
			t.Fatalf("Android OAEP parameters failed: %v", err)
		}
		if _, err := key.Decrypt(rand.Reader, encrypted, &rsa.OAEPOptions{Hash: stdcrypto.SHA256, MGFHash: stdcrypto.SHA256}); err == nil {
			t.Fatal("wrong MGF1 digest succeeded")
		}
	}
	if _, err := encryptAndroidOAEP(&key.PublicKey, make([]byte, 191)); err != rsa.ErrMessageTooLong {
		t.Fatalf("oversize message accepted: %v", err)
	}
	if _, err := encryptAndroidOAEP(nil, nil); err == nil {
		t.Fatal("nil key accepted")
	}
	message := make([]byte, AESKeySize)
	a, err := encryptAndroidOAEP(&key.PublicKey, message)
	if err != nil {
		t.Fatal(err)
	}
	b, err := encryptAndroidOAEP(&key.PublicKey, message)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(a, b) {
		t.Fatal("OAEP seed was reused")
	}
}

// Verify against an independent implementation when OpenSSL is available.
func TestAndroidOAEPWithOpenSSL(t *testing.T) {
	tool, err := exec.LookPath("openssl")
	if err != nil {
		t.Skip("OpenSSL unavailable")
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	message := []byte("Android OAEP interoperability")
	encrypted, err := encryptAndroidOAEP(&key.PublicKey, message)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "test-key.pem")
	cipherPath := filepath.Join(dir, "ciphertext.bin")
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cipherPath, encrypted, 0600); err != nil {
		t.Fatal(err)
	}
	plain, err := exec.Command(tool, "pkeyutl", "-decrypt", "-inkey", keyPath, "-in", cipherPath,
		"-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", "-pkeyopt", "rsa_mgf1_md:sha1").CombinedOutput()
	if err != nil || !bytes.Equal(plain, message) {
		t.Fatalf("OpenSSL interoperability failed: %v", err)
	}
}
