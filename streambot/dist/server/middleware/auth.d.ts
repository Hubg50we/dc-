import { Request, Response, NextFunction } from 'express';
export declare const authMiddleware: (req: Request, res: Response, next: NextFunction) => void;
export declare const requireAuth: (req: Request, res: Response, next: NextFunction) => void;
