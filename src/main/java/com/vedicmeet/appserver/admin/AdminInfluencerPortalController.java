package com.vedicmeet.appserver.admin;

import org.springframework.web.bind.annotation.RestController;

/**
 * FAITHFUL(node-quirk): Node rest-apis/modules/admin/influencer-portal.js exports an empty object
 * (module.exports = {}) and registers no routes; the portal views are served by the existing
 * /admin/v1/influencers endpoints (see AdminInfluencersController). No endpoints are exposed here.
 */
@RestController
public class AdminInfluencerPortalController {
}