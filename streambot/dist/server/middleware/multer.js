import multer from 'multer';
import config from '../../config.js';
export const storage = multer.diskStorage({
    destination: (req, file, cb) => {
        cb(null, config.videosDir);
    },
    filename: (req, file, cb) => {
        cb(null, file.originalname);
    },
});
export const upload = multer({ storage });
//# sourceMappingURL=multer.js.map