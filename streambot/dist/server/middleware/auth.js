export const authMiddleware = (req, res, next) => {
    if (req.session.user) {
        next();
    }
    else {
        res.redirect("/login");
    }
};
export const requireAuth = (req, res, next) => {
    if (req.path === "/login") {
        next();
    }
    else {
        authMiddleware(req, res, next);
    }
};
//# sourceMappingURL=auth.js.map