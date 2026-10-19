package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"io"
)

const (
	// AESKeySize is the size of AES-256 key in bytes
	AESKeySize = 32
	// NonceSize is the size of GCM nonce in bytes
	NonceSize = 12
)

// Crypto handles public-key message encryption.
type Crypto struct{}

// NewCrypto creates a new Crypto instance
func NewCrypto() *Crypto {
	return &Crypto{}
}

// ParsePublicKey parses a PEM-encoded RSA public key
func (c *Crypto) ParsePublicKey(pemKey string) (*rsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemKey))
	if block == nil {
		return nil, errors.New("failed to parse PEM block")
	}

	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}

	rsaPub, ok := pub.(*rsa.PublicKey)
	if !ok {
		return nil, errors.New("not an RSA public key")
	}

	return rsaPub, nil
}

// EncryptMessage encrypts a message using hybrid encryption (RSA + AES-GCM)
// Returns: base64(encrypted_aes_key + nonce + ciphertext)
func (c *Crypto) EncryptMessage(publicKey *rsa.PublicKey, plaintext []byte) (string, error) {
	return c.encryptMessage(publicKey, plaintext, false)
}

func (c *Crypto) EncryptPCMessage(publicKey *rsa.PublicKey, plaintext []byte) (string, error) {
	return c.encryptMessage(publicKey, plaintext, true)
}

func (c *Crypto) encryptMessage(publicKey *rsa.PublicKey, plaintext []byte, pc bool) (string, error) {
	// Generate random AES key
	aesKey := make([]byte, AESKeySize)
	if _, err := io.ReadFull(rand.Reader, aesKey); err != nil {
		return "", err
	}

	// Match Android Keystore: OAEP SHA-256, MGF1 SHA-1, empty label.
	var encryptedKey []byte
	var err error
	if pc {
		encryptedKey, err = rsa.EncryptOAEP(sha256.New(), rand.Reader, publicKey, aesKey, nil)
	} else {
		encryptedKey, err = encryptAndroidOAEP(publicKey, aesKey)
	}
	if err != nil {
		return "", err
	}

	// Create AES-GCM cipher
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	// Generate nonce
	nonce := make([]byte, NonceSize)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}

	// Encrypt message with AES-GCM
	ciphertext := gcm.Seal(nil, nonce, plaintext, nil)

	// Combine: encrypted_aes_key + nonce + ciphertext
	// Format: [2 bytes key length][encrypted key][12 bytes nonce][ciphertext]
	keyLen := len(encryptedKey)
	result := make([]byte, 2+keyLen+NonceSize+len(ciphertext))
	result[0] = byte(keyLen >> 8)
	result[1] = byte(keyLen & 0xff)
	copy(result[2:], encryptedKey)
	copy(result[2+keyLen:], nonce)
	copy(result[2+keyLen+NonceSize:], ciphertext)

	return base64.StdEncoding.EncodeToString(result), nil
}

// GenerateDeviceKey generates a random 32-character device key
func (c *Crypto) GenerateDeviceKey() (string, error) {
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
	result := make([]byte, 0, 32)
	// Reject the biased tail instead of reducing every random byte modulo 62.
	for len(result) < 32 {
		var b [32]byte
		if _, err := io.ReadFull(rand.Reader, b[:]); err != nil {
			return "", err
		}
		for _, value := range b {
			if value < 248 && len(result) < 32 {
				result = append(result, alphabet[int(value)%len(alphabet)])
			}
		}
	}
	return string(result), nil
}
