import { Router } from 'express';
import bcrypt from 'bcrypt';
import argon2 from 'argon2';
import config from '../../config.js';
import logger from '../../utils/logger.js';
const router = Router();
router.get("/login", (req, res) => {
    res.render('pages/login', {
        error: req.query.error === '1',
        showLogout: false
    });
});
router.post("/login", async (req, res) => {
    const { username, password } = req.body;
    let isPasswordMatch = false;
    if (config.server_password.startsWith('$argon2')) {
        try {
            isPasswordMatch = await argon2.verify(config.server_password, password);
        }
        catch (err) {
            logger.error("Error verifying argon2 password:", err);
            isPasswordMatch = false;
        }
    }
    else if (config.server_password.startsWith('$2')) {
        isPasswordMatch = await bcrypt.compare(password, config.server_password);
    }
    else {
        isPasswordMatch = password === config.server_password;
    }
    if (username === config.server_username && isPasswordMatch) {
        req.session.user = username;
        res.redirect("/");
    }
    else {
        res.redirect("/login?error=1");
    }
});
router.get("/logout", (req, res) => {
    req.session.destroy((err) => {
        if (err) {
            logger.error("Error destroying session:", err);
        }
        res.redirect("/login");
    });
});
export default router;
//# sourceMappingURL=auth.js.map