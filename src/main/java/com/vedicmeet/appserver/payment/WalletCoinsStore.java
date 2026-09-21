package com.vedicmeet.appserver.payment;

/**
 * Port for the AES-encrypted wallet {@code coins} balance (the {@code wallets} collection /
 * Node WalletModel). Only the raw ciphertext string crosses this boundary; the encrypt/decrypt
 * (the money-safety invariant) lives in {@link AesWalletService} so it is unit-tested with a REAL
 * {@link com.vedicmeet.appserver.crypto.CryptoUtil}.
 */
public interface WalletCoinsStore {

    /** WalletModel.findOne({ userId }, { coins:1 }).coins — the encrypted string, or null. */
    String findUserCoinsCipher(Object userId);

    void setUserCoinsCipher(Object userId, String cipher);

    /** WalletModel.findOne({ consultantId }, { coins:1 }).coins — the encrypted string, or null. */
    String findConsultantCoinsCipher(Object consultantId);

    void setConsultantCoinsCipher(Object consultantId, String cipher);
}
