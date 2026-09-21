package com.vedicmeet.appserver.admin;

import org.springframework.stereotype.Service;

/**
 * FAITHFUL(node-quirk): rest-apis/modules/admin/influencer-portal.js is `module.exports = {}` and
 * registers NO routes. Its header comments state that the influencer portal UI reuses the existing
 * `influencers` endpoints (/metrics, /ledger, /settlements). This module therefore intentionally
 * exposes no logic; the real portal-backing logic lives in AdminInfluencersService.
 */
@Service
public class AdminInfluencerPortalService {
}